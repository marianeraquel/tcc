/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package terasort;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.hadoop.util.IndexedSortable;
import org.apache.hadoop.util.QuickSort;
import org.apache.hadoop.util.StringUtils;

/**
 * An input format that reads the first 10 characters of each line as the key
 * and the rest of the line as the value. Both key and value are represented
 * as Text.
 */
public class TeraInputFormat extends FileInputFormat {

  static final String PARTITION_FILENAME = "_partition.lst";
  private static final String NUM_PARTITIONS =
    "mapreduce.terasort.num.partitions";
  private static final String SAMPLE_SIZE =
    "mapreduce.terasort.partitions.sample";
  static final int KEY_LENGTH = 10;
  static final int VALUE_LENGTH = 90;
  static final int RECORD_LENGTH = KEY_LENGTH + VALUE_LENGTH;
  private static MRJobConfig lastContext = null;
  private static List lastResult = null;

  static class TeraFileSplit extends FileSplit {
    private String[] locations;
    public TeraFileSplit() {}
    public TeraFileSplit(Path file, long start, long length, String[] hosts) {
      super(file, start, length, hosts);
      locations = hosts;
    }
    protected void setLocations(String[] hosts) {
      locations = hosts;
    }
    @Override
    public String[] getLocations() {
      return locations;
    }
    public String toString() {
      StringBuffer result = new StringBuffer();
      result.append(getPath());
      result.append(" from ");
      result.append(getStart());
      result.append(" length ");
      result.append(getLength());
      for(String host: getLocations()) {
        result.append(" ");
        result.append(host);
      }
      return result.toString();
    }
  }

  static class TextSampler implements IndexedSortable {
    private ArrayList records = new ArrayList();

    public int compare(int i, int j) {
      Text left = records.get(i);
      Text right = records.get(j);
      return left.compareTo(right);
    }

    public void swap(int i, int j) {
      Text left = records.get(i);
      Text right = records.get(j);
      records.set(j, left);
      records.set(i, right);
    }

    public void addKey(Text key) {
      synchronized (this) {
        records.add(new Text(key));
      }
    }

    /**
     * Find the split points for a given sample. The sample keys are sorted
     * and down sampled to find even split points for the partitions. The
     * returned keys should be the start of their respective partitions.
     * @param numPartitions the desired number of partitions
     * @return an array of size numPartitions - 1 that holds the split points
     */
    Text[] createPartitions(int numPartitions) {
      int numRecords = records.size();
      System.out.println("Making " + numPartitions + " from " + numRecords +
                         " sampled records");
      if (numPartitions > numRecords) {
        throw new IllegalArgumentException
          ("Requested more partitions than input keys (" + numPartitions +
           " > " + numRecords + ")");
      }
      new QuickSort().sort(this, 0, records.size());
      float stepSize = numRecords / (float) numPartitions;
      Text[] result = new Text[numPartitions-1];
      for(int i=1; i < numPartitions; ++i) {
        result[i-1] = records.get(Math.round(stepSize * i));
      }
      return result;
    }
  }

  /**
   * Use the input splits to take samples of the input and generate sample
   * keys. By default reads 100,000 keys from 10 locations in the input, sorts
   * them and picks N-1 keys to generate N equally sized partitions.
   * @param job the job to sample
   * @param partFile where to write the output file to
   * @throws IOException if something goes wrong
   */
  public static void writePartitionFile(final JobContext job,
      Path partFile) throws IOException, InterruptedException  {
    long t1 = System.currentTimeMillis();
    Configuration conf = job.getConfiguration();
    final TeraInputFormat inFormat = new TeraInputFormat();
    final TextSampler sampler = new TextSampler();
    int partitions = job.getNumReduceTasks();
    long sampleSize = conf.getLong(SAMPLE_SIZE, 100000);
    final List splits = inFormat.getSplits(job);
    long t2 = System.currentTimeMillis();
    System.out.println("<em>Computing</em> input splits took " + (t2 - t1) + "ms");
    int samples = Math.min(conf.getInt(NUM_PARTITIONS, 10), splits.size());
    System.out.println("Sampling " + samples + " splits of " + splits.size());
    final long recordsPerSample = sampleSize / samples;
    final int sampleStep = splits.size() / samples;
    Thread[] samplerReader = new Thread[samples];
    // take N samples from different parts of the input
    for(int i=0; i < samples; ++i) {
      final int idx = i;
      samplerReader[i] =
        new Thread ("Sampler Reader " + idx) {
        {
          setDaemon(true);
        }
        public void run() {
          long records = 0;
          try {
            TaskAttemptContext context = new TaskAttemptContextImpl(
              job.getConfiguration(), new TaskAttemptID());
            RecordReader reader =
              inFormat.createRecordReader(splits.get(sampleStep * idx),
              context);
            reader.initialize(splits.get(sampleStep * idx), context);
            while (reader.nextKeyValue()) {
              sampler.addKey(new Text(reader.getCurrentKey()));
              records += 1;
              if (recordsPerSample <= records) {
                break;
              }
            }
          } catch (IOException ie){
            System.err.println("Got an exception while reading splits " +
                StringUtils.stringifyException(ie));
            System.exit(-1);
          } catch (InterruptedException e) {

          }
        }
      };
      samplerReader[i].start();
    }
    FileSystem outFs = partFile.getFileSystem(conf);
    DataOutputStream writer = outFs.create(partFile, true, 64*1024, (short) 10,
                                           outFs.getDefaultBlockSize());
    for (int i = 0; i < samples; i++) {
      try {
        samplerReader[i].join();
      } catch (InterruptedException e) {
      }
    }
    for(Text split : sampler.createPartitions(partitions)) {
      split.write(writer);
    }
    writer.<em>close</em>();
    long t3 = System.currentTimeMillis();
    System.out.println("<em>Computing</em> parititions took " + (t3 - t2) + "ms");
  }

  static class TeraRecordReader extends RecordReader {
    private FSDataInputStream in;
    private long offset;
    private long length;
    private static final int RECORD_LENGTH = KEY_LENGTH + VALUE_LENGTH;
    private byte[] buffer = new byte[RECORD_LENGTH];
    private Text key;
    private Text value;

    public TeraRecordReader() throws IOException {
    }

    public void initialize(InputSplit split, TaskAttemptContext context)
        throws IOException, InterruptedException {
      Path p = ((FileSplit)split).getPath();
      FileSystem fs = p.getFileSystem(context.getConfiguration());
      in = fs.open(p);
      long start = ((FileSplit)split).getStart();
      // find the offset to start at a record boundary
      offset = (RECORD_LENGTH - (start % RECORD_LENGTH)) % RECORD_LENGTH;
      in.seek(start + offset);
      length = ((FileSplit)split).getLength();
    }

    public void <em>close</em>() throws IOException {
      in.<em>close</em>();
    }

    public Text getCurrentKey() {
      return key;
    }

    public Text getCurrentValue() {
      return value;
    }

    public float getProgress() throws IOException {
      return (float) offset / length;
    }

    public boolean nextKeyValue() throws IOException {
      if (offset >= length) {
        return false;
      }
      int read = 0;
      while (read < RECORD_LENGTH) {
        long newRead = in.read(buffer, read, RECORD_LENGTH - read);
        if (newRead == -1) {
          if (read == 0) {
            return false;
          } else {
            throw new EOFException("read past eof");
          }
        }
        read += newRead;
      }
      if (key == null) {
        key = new Text();
      }
      if (value == null) {
        value = new Text();
      }
      key.set(buffer, 0, KEY_LENGTH);
      value.set(buffer, KEY_LENGTH, VALUE_LENGTH);
      offset += RECORD_LENGTH;
      return true;
    }
  }

  @Override
  public RecordReader
      createRecordReader(InputSplit split, TaskAttemptContext context)
      throws IOException {
    return new TeraRecordReader();
  }

  protected FileSplit makeSplit(Path file, long start, long length,
                                String[] hosts) {
    return new TeraFileSplit(file, start, length, hosts);
  }

  @Override
  public List getSplits(JobContext job) throws IOException {
    if (job == lastContext) {
      return lastResult;
    }
    long t1, t2, t3;
    t1 = System.currentTimeMillis();
    lastContext = job;
    lastResult = super.getSplits(job);
    t2 = System.currentTimeMillis();
    System.out.println("Spent " + (t2 - t1) + "ms <em>computing</em> base-splits.");
    if (job.getConfiguration().getBoolean(TeraScheduler.USE, true)) {
      TeraScheduler scheduler = new TeraScheduler(
        lastResult.toArray(new TeraFileSplit[0]), job.getConfiguration());
      lastResult = scheduler.getNewFileSplits();
      t3 = System.currentTimeMillis();
      System.out.println("Spent " + (t3 - t2) + "ms <em>computing</em> TeraScheduler splits.");
    }
    return lastResult;
  }
}
