package cn.uc.hadoop.mapreduce.lib.output;

import java.io.IOException;
import java.util.Iterator;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;

/**
 * This abstract class extends the FileOutputFormat, allowing to write the
 * output data to different output files. There are three basic use cases for
 * this class.
 * 
 * Case one: This class is used for a map reduce job with at least one reducer.
 * The reducer wants to write data to different files depending on the actual
 * keys. It is assumed that a key (or value) encodes the actual key (value) and
 * the desired location for the actual key (value).
 * 
 * Case two: This class is used for a map only job. The job wants to use an
 * output file name that is either a part of the input file name of the input
 * data, or some derivation of it.
 * 
 * Case three: This class is used for a map only job. The job wants to use an
 * output file name that depends on both the keys and the input file name,
 */
abstract class MultipleOutputFormat<K, V> extends FileOutputFormat<K, V> {

	/**
	 * Generate the actual key from the given key/value. The default behavior is
	 * that the actual key is equal to the given key
	 * 
	 * @param key
	 *            the key of the output data
	 * @param value
	 *            the value of the output data
	 * @return the actual key derived from the given key/value
	 */
	protected K generateActualKey(K key, V value) {
		return key;
	}

	/**
	 * Generate the actual value from the given key and value. The default
	 * behavior is that the actual value is equal to the given value
	 * 
	 * @param key
	 *            the key of the output data
	 * @param value
	 *            the value of the output data
	 * @return the actual value derived from the given key/value
	 */
	protected V generateActualValue(K key, V value) {
		return value;
	}

	/**
	 * Generate the file output file name based on the given key and the leaf
	 * file name. The default behavior is that the file name does not depend on
	 * the key.
	 * 
	 * @param key
	 *            the key of the output data
	 * @param name
	 *            the leaf file name
	 * @return generated file name
	 */
	protected String generateFileNameForKeyValue(K key, V value, String name) {
		return name;
	}

	/**
	 * Generate the leaf name for the output file name. The default behavior
	 * does not change the leaf file name (such as part-00000)
	 * 
	 * @param name
	 *            the leaf file name for the output file
	 * @return the given leaf file name
	 */
	protected String generateLeafFileName(String name) {
		return name;
	}

	/**
	 * 
	 * @param fs
	 *            the file system to use
	 * @param job
	 *            a job conf object
	 * 
	 * @param name
	 *            the name of the file over which a record writer object will be
	 *            constructed
	 * @param arg3
	 *            a progressable object
	 * @return A RecordWriter object over the given file
	 * @throws IOException
	 */
	abstract protected RecordWriter<K, V> getBaseRecordWriter(
			TaskAttemptContext job, String name) throws IOException,
			InterruptedException;

	/**
	 * Generate the outfile name based on a given name and the input file name.
	 * If the map input file does not exists (i.e. this is not for a map only
	 * job), the given name is returned unchanged. If the config value for
	 * "num.of.trailing.legs.to.use" is not set, or set 0 or negative, the given
	 * name is returned unchanged. Otherwise, return a file name consisting of
	 * the N trailing legs of the input file name where N is the config value
	 * for "num.of.trailing.legs.to.use".
	 * 
	 * @param job
	 *            the job config
	 * @param name
	 *            the output file name
	 * @return the outfile name based on a given anme and the input file name.
	 */
	protected String getInputFileBasedOutputFileName(Configuration job,
			String name) {

		String infilepath = job.get("map.input.file");
		if (infilepath == null) {
			// if the map input file does not exists, then return the given name
			return name;
		}
		int numOfTrailingLegsToUse = job.getInt(
				"mapred.outputformat.numOfTrailingLegs", 0);
		if (numOfTrailingLegsToUse <= 0) {
			return name;
		}
		Path infile = new Path(infilepath);
		Path parent = infile.getParent();
		String midName = infile.getName();
		Path outPath = new Path(midName);
		for (int i = 1; i < numOfTrailingLegsToUse; i++) {
			if (parent == null) {
				break;
			}
			midName = parent.getName();
			if (midName.length() == 0) {
				break;
			}
			parent = parent.getParent();
			outPath = new Path(midName, outPath);
		}
		return outPath.toString();
	}

	/**
	 * Create a composite record writer that can write key/value data to
	 * different output files
	 * 
	 * @param fs
	 *            the file system to use
	 * @param job
	 *            the job conf for the job
	 * @param name
	 *            the leaf file name for the output file (such as part-00000")
	 * @param arg3
	 *            a progressable for reporting progress.
	 * @return a composite record writer
	 * @throws IOException
	 */
	@Override
	public RecordWriter<K, V> getRecordWriter(final TaskAttemptContext job)
			throws IOException {
		// 扩展名？
		String name = getUniqueFile(job, getOutputName(job), "");
		final String myName = generateLeafFileName(name);
		final Configuration conf = job.getConfiguration();
		// final Progressable myProgressable = arg3;

		return new RecordWriter<K, V>() {

			// a cache storing the record writers for different output files.
			TreeMap<String, RecordWriter<K, V>> recordWriters = new TreeMap<String, RecordWriter<K, V>>();

			@Override
			public void close(TaskAttemptContext context) throws IOException,
					InterruptedException {
				Iterator<String> keys = this.recordWriters.keySet().iterator();
				while (keys.hasNext()) {
					RecordWriter<K, V> rw = this.recordWriters.get(keys.next());
					rw.close(context);
				}
				this.recordWriters.clear();
			}

			@Override
			public void write(K key, V value) throws IOException,
					InterruptedException {

				// get the file name based on the key
				String keyBasedPath = generateFileNameForKeyValue(key, value,
						myName);

				// get the file name based on the input file name
				String finalPath = getInputFileBasedOutputFileName(conf,
						keyBasedPath);

				// get the actual key
				K actualKey = generateActualKey(key, value);
				V actualValue = generateActualValue(key, value);

				RecordWriter<K, V> rw = this.recordWriters.get(finalPath);
				if (rw == null) {
					// if we don't have the record writer yet for the final
					// path, create
					// one
					// and add it to the cache
					rw = getBaseRecordWriter(job, finalPath);
					this.recordWriters.put(finalPath, rw);
				}
				rw.write(actualKey, actualValue);
			};
		};
	}
}

/**
 * 多输出类。 本类仅测试过以key为文件名时是安全的（并且要求相同的Key必须落在同JVM中），其它的使用方式可能存在问题。
 * 
 * @author zhaigy
 */
public class MultipleTextOutputFormat<K, V> extends MultipleOutputFormat<K, V> {
	@Override
	protected RecordWriter<K, V> getBaseRecordWriter(TaskAttemptContext job,
			final String name) throws IOException, InterruptedException {
		return new TextOutputFormat<K, V>() {
			// 重要的overwrite
			@Override
			public Path getDefaultWorkFile(TaskAttemptContext context,
					String extension) throws IOException {
				FileOutputCommitter committer = (FileOutputCommitter) getOutputCommitter(context);
				return new Path(committer.getWorkPath(), name + extension);
			}
		}.getRecordWriter(job);
	}
}
