/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package randomwriter;

import java.io.IOException;
import java.util.Date;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.lib.IdentityReducer;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;


public class RandomWriter extends Configured implements Tool {

  /**
   * User counters
   */
  static enum Counters { RECORDS_WRITTEN, BYTES_WRITTEN }

  /**
   * A custom input format that creates virtual inputs of a single string
   * for each map.
   */
  static class RandomInputFormat implements InputFormat<IntWritable, Text> {

    /**
     * Generate the requested number of file splits, with the filename
     * set to the filename of the output file.
     */
    public InputSplit[] getSplits(JobConf job,
                                  int numSplits) throws IOException {
      InputSplit[] result = new InputSplit[numSplits];
      Path outDir = FileOutputFormat.getOutputPath(job);
      for(int i=0; i < result.length; ++i) {
        result[i] = new FileSplit(new Path(outDir, "dummy-split-" + i), 0, 1,
                                  (String[])null);
      }
      return result;
    }

        public RecordReader<IntWritable, Text> getRecordReader(InputSplit split, JobConf job, Reporter reporter) throws IOException
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

    /**
     * Return a single record (filename, "") where the filename is taken from
     * the file split.
     */
    static class RandomRecordReader implements RecordReader<IntWritable, Text> {
      Path name;
      public RandomRecordReader(Path p) {
        name = p;
      }
      public boolean next(IntWritable key, Text value) {
        if (name != null) {
          //key.set(name.getName());
          name = null;
          return true;
        }
        return false;
      }
      public Text createKey() {
        return new IntWritable();
      }
      public Text createValue() {
        return new Text();
      }
      public long getPos() {
        return 0;
      }
      public void close() {}
      public float getProgress() {
        return 0.0f;
      }

            public IntWritable createKey()
            {
                throw new UnsupportedOperationException("Not supported yet.");
            }
    }

    public RecordReader<IntWritable, Text> getRecordReader(InputSplit split,
                                        JobConf job,
                                        Reporter reporter) throws IOException {
      return new RandomRecordReader(((FileSplit) split).getPath());
    }
  }

  static class Map extends MapReduceBase
    implements Mapper<WritableComparable, Writable,
                      IntWritable, IntWritable> {

    private long numBytesToWrite;
    private int minKeySize;
    private int keySizeRange;
    private int minValueSize;
    private int valueSizeRange;
    private Random random = new Random();
    private IntWritable randomKey = new IntWritable();
    private IntWritable randomValue = new IntWritable();

    private void randomizeBytes(byte[] data, int offset, int length) {
      for(int i=offset + length - 1; i >= offset; --i) {
        data[i] = (byte) random.nextInt(256);
      }
    }

    /**
     * Given an output filename, write a bunch of random records to it.
     */
    public void map(WritableComparable key,
                    Writable value,
                    OutputCollector<IntWritable, IntWritable> output,
                    Reporter reporter) throws IOException {
      int itemCount = 0;
      while (numBytesToWrite > 0) {
        int keyLength = minKeySize +
          (keySizeRange != 0 ? random.nextInt(keySizeRange) : 0);
        randomKey.setSize(keyLength);
        randomizeBytes(randomKey.getBytes(), 0, randomKey.getLength());
        int valueLength = minValueSize +
          (valueSizeRange != 0 ? random.nextInt(valueSizeRange) : 0);
        randomValue.setSize(valueLength);
        randomizeBytes(randomValue.getBytes(), 0, randomValue.getLength());
        output.collect(randomKey, randomValue);
        numBytesToWrite -= keyLength + valueLength;
        reporter.incrCounter(Counters.BYTES_WRITTEN, keyLength + valueLength);
        reporter.incrCounter(Counters.RECORDS_WRITTEN, 1);
        if (++itemCount % 200 == 0) {
          reporter.setStatus("wrote record " + itemCount + ". " +
                             numBytesToWrite + " bytes left.");
        }
      }
      reporter.setStatus("done with " + itemCount + " records.");
    }

    /**
     * Save the values out of the configuaration that we need to write
     * the data.
     */
    @Override
    public void configure(JobConf job) {
      numBytesToWrite = job.getLong("test.randomwrite.bytes_per_map",
                                    1*1024*1024*1024);
      minKeySize = job.getInt("test.randomwrite.min_key", 10);
      keySizeRange =
        job.getInt("test.randomwrite.max_key", 1000) - minKeySize;
      minValueSize = job.getInt("test.randomwrite.min_value", 0);
      valueSizeRange =
        job.getInt("test.randomwrite.max_value", 20000) - minValueSize;
    }

  }

  /**
   * This is the main routine for launching a distributed random write job.
   * It runs 10 maps/node and each node writes 1 gig of data to a DFS file.
   * The reduce doesn't do anything.
   *
   * @throws IOException
   */
  public int run(String[] args) throws Exception {
    if (args.length == 0) {
      System.out.println("Usage: writer <out-dir>");
      ToolRunner.printGenericCommandUsage(System.out);
      return -1;
    }

    Path outDir = new Path(args[0]);
    JobConf job = new JobConf(getConf());

    job.setJarByClass(RandomWriter.class);
    job.setJobName("random-writer");
    FileOutputFormat.setOutputPath(job, outDir);

    job.setOutputKeyClass(BytesWritable.class);
    job.setOutputValueClass(BytesWritable.class);

    job.setInputFormat(RandomInputFormat.class);
    job.setMapperClass(Map.class);
    job.setReducerClass(IdentityReducer.class);
    job.setOutputFormat(SequenceFileOutputFormat.class);

    JobClient client = new JobClient(job);
    ClusterStatus cluster = client.getClusterStatus();
    int numMapsPerHost = job.getInt("test.randomwriter.maps_per_host", 10);
    long numBytesToWritePerMap = job.getLong("test.randomwrite.bytes_per_map",
                                             1*1024*1024*1024);
    if (numBytesToWritePerMap == 0) {
      System.err.println("Cannot have test.randomwrite.bytes_per_map set to 0");
      return -2;
    }
    long totalBytesToWrite = job.getLong("test.randomwrite.total_bytes",
         numMapsPerHost*numBytesToWritePerMap*cluster.getTaskTrackers());
    int numMaps = (int) (totalBytesToWrite / numBytesToWritePerMap);
    if (numMaps == 0 && totalBytesToWrite > 0) {
      numMaps = 1;
      job.setLong("test.randomwrite.bytes_per_map", totalBytesToWrite);
    }

    job.setNumMapTasks(numMaps);
    System.out.println("Running " + numMaps + " maps.");

    // reducer NONE
    job.setNumReduceTasks(0);

    Date startTime = new Date();
    System.out.println("Job started: " + startTime);
    JobClient.runJob(job);
    Date endTime = new Date();
    System.out.println("Job ended: " + endTime);
    System.out.println("The job took " +
                       (endTime.getTime() - startTime.getTime()) /1000 +
                       " seconds.");

    return 0;
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Configuration(), new RandomWriter(), args);
    System.exit(res);
  }

}
