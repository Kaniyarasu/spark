package spark.rdd

import java.text.SimpleDateFormat
import java.util.Date

import org.apache.hadoop.conf.{Configurable, Configuration}
import org.apache.hadoop.io.Writable
import org.apache.hadoop.mapreduce._

import spark.{Dependency, Logging, Partition, RDD, SerializableWritable, SparkContext, TaskContext}


private[spark]
class NewHadoopPartition(rddId: Int, val index: Int, @transient rawSplit: InputSplit with Writable)
  extends Partition {

  val serializableHadoopSplit = new SerializableWritable(rawSplit)

  override def hashCode(): Int = (41 * (41 + rddId) + index)
}

class NewHadoopRDD[K, V](
    sc : SparkContext,
    inputFormatClass: Class[_ <: InputFormat[K, V]],
    keyClass: Class[K],
    valueClass: Class[V],
    @transient conf: Configuration)
  extends RDD[(K, V)](sc, Nil)
  with HadoopMapReduceUtil
  with Logging {

  // A Hadoop Configuration can be about 10 KB, which is pretty big, so broadcast it
  private val confBroadcast = sc.broadcast(new SerializableWritable(conf))
  // private val serializableConf = new SerializableWritable(conf)

  private val jobtrackerId: String = {
    val formatter = new SimpleDateFormat("yyyyMMddHHmm")
    formatter.format(new Date())
  }

  @transient private val jobId = new JobID(jobtrackerId, id)

  override def getPartitions: Array[Partition] = {
    val inputFormat = inputFormatClass.newInstance
    if (inputFormat.isInstanceOf[Configurable]) {
      inputFormat.asInstanceOf[Configurable].setConf(conf)
    }
    val jobContext = newJobContext(conf, jobId)
    val rawSplits = inputFormat.getSplits(jobContext).toArray
    val result = new Array[Partition](rawSplits.size)
    for (i <- 0 until rawSplits.size) {
      result(i) = new NewHadoopPartition(id, i, rawSplits(i).asInstanceOf[InputSplit with Writable])
    }
    result
  }

  override def compute(theSplit: Partition, context: TaskContext) = new Iterator[(K, V)] {
    val split = theSplit.asInstanceOf[NewHadoopPartition]
    val conf = confBroadcast.value.value
    val attemptId = newTaskAttemptID(jobtrackerId, id, true, split.index, 0)
    val hadoopAttemptContext = newTaskAttemptContext(conf, attemptId)
    val format = inputFormatClass.newInstance
    if (format.isInstanceOf[Configurable]) {
      format.asInstanceOf[Configurable].setConf(conf)
    }
    val reader = format.createRecordReader(
      split.serializableHadoopSplit.value, hadoopAttemptContext)
    reader.initialize(split.serializableHadoopSplit.value, hadoopAttemptContext)

    // Register an on-task-completion callback to close the input stream.
    context.addOnCompleteCallback(() => close())

    var havePair = false
    var finished = false

    override def hasNext: Boolean = {
      if (!finished && !havePair) {
        finished = !reader.nextKeyValue
        havePair = !finished
      }
      !finished
    }

    override def next: (K, V) = {
      if (!hasNext) {
        throw new java.util.NoSuchElementException("End of stream")
      }
      havePair = false
      return (reader.getCurrentKey, reader.getCurrentValue)
    }

    private def close() {
      try {
        reader.close()
      } catch {
        case e: Exception => logWarning("Exception in RecordReader.close()", e)
      }
    }
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    val theSplit = split.asInstanceOf[NewHadoopPartition]
    theSplit.serializableHadoopSplit.value.getLocations.filter(_ != "localhost")
  }
}
