import java.io.{PrintWriter, File}
import breeze.linalg._
import breeze.numerics._
import edu.umd.cloud9.math.Gamma
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileSystem
import org.apache.spark.serializer.KryoRegistrator
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import com.esotericsoftware.kryo.Kryo

class LDARegistrator extends KryoRegistrator {
  override def registerClasses(kryo: Kryo) {
    kryo.register(classOf[DenseVector[Double]])
    kryo.register(classOf[DenseMatrix[Double]])
    kryo.register(classOf[LdaSettings])
  }
}
object LDA {
  val setting = new LdaSettings();
  /*
  Entry point for computing LDA.
  args:
  0: Input Path           -- A path to the folder containing a set of sequence files in vector space model.
  1: Vocabulary count     -- An upper bound on the number of distinct words in the corpus
  2: Number of Topics     -- The number of topics to infer
  3: OutputPath           -- A path to the folder to store the final results (Lambda matrix)
  4: Settings file path   -- A path to the settings file containing the training parameters (See example_settings)
  */
  def computeTopics(args: Array[String]) {
    val sc = createSparkContext();
    val DELTA = -1; //Special key to distinguish between Beta and Gamma updates in the reducer.

    val input = args(0);
    val V = args(1).toInt
    val K = args(2).toInt
    val output = args(3);
    val settingsFilePath = args(4);
    setting.getSettings(settingsFilePath);

    //Read vectorized data set
    //Every document is represented in the data set as k1:v1 k2:v2 .. Kn:vn, It is then mapped to a an array of key,value pairs
    val documents = sc.sequenceFile[String,String](input,200)
      .zipWithIndex()
      .map({ case((key, value), index) =>
              {
                val doc = value ;//cur._1._2
                val doc_id = index;//cur._2
                val document_elements = doc.split(" ");
                //Parse every document element from key:value string to a tuple(key, value)
                val document_words = document_elements.map(e => {
                  val params = e.split(":");
                  Tuple2(params(0).toInt, params(1).toInt);
                })
                document_words
    }}).cache();

    //Initialize Global Variables
    val D = documents.count().toInt;
    var alpha = DenseVector.fill[Double](K){setting.ALPHA_INITIALIZATION}
    var lambda = DenseMatrix.fill[Double](V,K){ Math.random() / (Math.random() + V) };
    val sufficientStats = DenseVector.zeros[Double](K);

    for(global_iteration <- 0 until setting.MAX_GLOBAL_ITERATION) {
      //Broadcast lambda at the beginning of every iteration
      val lambda_broadcast = sc.broadcast(lambda);
      documents.flatMap(document => {
        val digammaCache = DenseVector.zeros[Double](K);
        val gamma = DenseVector.fill[Double](K){setting.GAMMA_INITIALIZATION + V/K };
        var emit = List[((Int, Int), Double)]()
        for (iteration <- 0 until setting.MAX_GAMMA_CONVERGENCE_ITERATION ) {
          val sigma = DenseVector.zeros[Double](K)
          //Pre-compute exp(digamma((gamma[k])) for efficency
          for(i <- 0 until K)
            digammaCache(i) = exp(Gamma.digamma(gamma(i)));
          for (word_ind <- 0 until document.length) {
            val v = document(word_ind)._1
            val count = document(word_ind)._2;
            var phi = DenseVector.zeros[Double](K);
            for (k <- 0 until K)
              phi(k) = lambda_broadcast.value(v, k) *digammaCache(k);
            //normalize phi vector
            val v_norm = sum(phi);
            phi = phi :* (1 / v_norm);
            sigma :+= (phi :* count.toDouble);
          }
          gamma := alpha + sigma;
        }
        //Emit the computed values
        for (word_ind <- 0 until document.length) {
          var current_phi = DenseVector.zeros[Double](K);
          val v = document(word_ind)._1;
          val count = document(word_ind)._2 ;
          for (k <- 0 until K)
            current_phi(k) = lambda_broadcast.value(v, k) * digammaCache(k);
          val v_norm = sum(current_phi);
          current_phi = current_phi :* (1 / v_norm);
          for (k <- 0 until K)
            emit = emit.+:((k, v), count * current_phi(k))
        }
        for (k <- 0 until K) {
          val sufficient_statistics = Gamma.digamma(gamma(k)) - Gamma.digamma((sum(gamma)))
          emit = emit.+:((k, DELTA), sufficient_statistics)
        }
        emit
      }).reduceByKey(_ + _)
        .collect()
        .foreach({ case ((k, v), aggregate)=>
        {
          if (v != DELTA)
            lambda(v, k) = setting.ETA + aggregate
          else
            sufficientStats(k) = aggregate;
        }});

      //normalize columns of lambda
      lambda = lambda.t;
      val norm = sum(lambda, Axis._1)
      for (k <- 0 until K) {
        lambda(k, ::) := lambda(k, ::) :* (1 / norm(k));
      }
      lambda = lambda.t;
      //Update alpha, for more details refer to section 3.4 in Mr. LDA
      alpha = updateAlphaVector(alpha, sufficientStats, K, D);
      saveMatrix(lambda, output+"/lambda_" + global_iteration);
    }
    saveMatrix(lambda, output+"/lambda_final");
  }

  def updateAlphaVector(alpha_0:DenseVector[Double], sufficientStats: DenseVector[Double], K: Int, D: Long) : DenseVector[Double] = {
    var keepGoing = true;
    var alphaIteration_count = 0;
    var alpha = alpha_0;
    while (keepGoing) {
      val gradient = DenseVector.zeros[Double](K);
      val qq = DenseVector.zeros[Double](K);
      for (i <- 0 until K) {
        gradient(i) = D * (Gamma.digamma(sum(alpha)) - Gamma.digamma(alpha(i))) + sufficientStats(i);
        qq(i) = -1 / (D * Gamma.trigamma(alpha(i)))
      }
      val z_1 = 1 / (D * Gamma.trigamma(sum(alpha)));
      val b = sum(gradient :* qq) / (z_1 + sum(qq));
      val step_size = (gradient - b) :* qq;
      var alpha_new = alpha;

      var decay = 0.0;
      var keepDecaying = true;
      //Make sure alpha vector stays positive within each update, this is done by decaying the step size till a
      //feasible alpha vector is returned.
      while(keepDecaying) {
        if(any( step_size * Math.pow(setting.ALPHA_UPDATE_DECAY_VALUE, decay)  :> alpha)) {
          decay = decay + 1;
        }
        else
        {
          alpha_new = alpha_new - step_size * Math.pow(setting.ALPHA_UPDATE_DECAY_VALUE, decay) ;
          keepDecaying = false;
        }
        if(decay > setting.ALPHA_UPDATE_MAXIMUM_DECAY)
          keepDecaying = false;
      }
      //Check for convergence of alpha vector
      val delta_alpha = abs((alpha_new - alpha) :/ alpha);
      keepGoing = false;
      if (any(delta_alpha :>  setting.DEFAULT_ALPHA_UPDATE_CONVERGE_THRESHOLD))
        keepGoing = true;
      if (alphaIteration_count > setting.ALPHA_MAX_ITERATION)
        keepGoing = false;
      alphaIteration_count += 1;
      alpha = alpha_new;
    }
    alpha
  }

  def saveMatrix(matrix: DenseMatrix[Double], outputPath: String) {
    val fs = FileSystem.get(new Configuration())
    val fsout = fs.create(new Path(outputPath), true);
    val pw = new PrintWriter(fsout);
    try {
      for (i <- 0 until matrix.rows) {
        var row = matrix(i, 0).toString();
        for (j <- 1 until matrix.cols) {
          row = row + " " + matrix(i, j).toString();
        }
        pw.println(row);
      }
    }
    finally pw.close()
  }

  def createSparkContext(): SparkContext = {
    val conf = new SparkConf().setAppName("SPARK LDA")
    conf.set("spark.default.parallelism","200");
    conf.set("spark.akka.frameSize","2000");
    conf.set("spark.akka.timeout","2000");
    //conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    //conf.set("spark.kryo.registrator", "LDARegistrator")
    conf.set("spark.kryoserializer.buffer.mb", "2000");
    if (!conf.contains("spark.master")) {
      conf.setMaster("local[*]")
      conf.set("data", "data/sample.warc")
    } else {
      conf.set("data", "/mnt/cw12/cw-data/ClueWeb12_00/")
    }
    new SparkContext(conf)
  }

  def main(args: Array[String]) {
    computeTopics(args)
  }
}