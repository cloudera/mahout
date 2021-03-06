/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.classifier.naivebayes.training;

import com.google.common.base.Splitter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.classifier.naivebayes.BayesUtils;
import org.apache.mahout.classifier.naivebayes.NaiveBayesModel;
import org.apache.mahout.common.AbstractJob;
import org.apache.mahout.common.HadoopUtil;
import org.apache.mahout.common.commandline.DefaultOptionCreator;
import org.apache.mahout.common.iterator.sequencefile.PathFilters;
import org.apache.mahout.common.iterator.sequencefile.PathType;
import org.apache.mahout.common.iterator.sequencefile.SequenceFileDirIterable;
import org.apache.mahout.common.mapreduce.VectorSumReducer;
import org.apache.mahout.math.VectorWritable;

import java.io.IOException;
import java.util.Map;

/**
 * This class trains a Naive Bayes Classifier (Parameters for both Naive Bayes and Complementary Naive Bayes)
 */
public final class TrainNaiveBayesJob extends AbstractJob {

  public static final String WEIGHTS_PER_FEATURE = "__SPF";
  public static final String WEIGHTS_PER_LABEL = "__SPL";
  public static final String LABEL_THETA_NORMALIZER = "_LTN";

  public static final String SUMMED_OBSERVATIONS = "summedObservations";
  public static final String WEIGHTS = "weights";
  public static final String THETAS = "thetas";

  public static void main(String[] args) throws Exception {
    ToolRunner.run(new Configuration(), new TrainNaiveBayesJob(), args);
  }

  @Override
  public int run(String[] args) throws Exception {

    addInputOption();
    addOutputOption();
    addOption("labels", "l", "comma-separated list of labels to include in training", false);

    addOption(buildOption("extractLabels", "el", "Extract the labels from the input", false, false, ""));
    addOption("alphaI", "a", "smoothing parameter", String.valueOf(1.0f));
    addOption(buildOption("trainComplementary", "c", "train complementary?", false, false, String.valueOf(false)));
    addOption("labelIndex", "li", "The path to store the label index in", false);
    addOption(DefaultOptionCreator.overwriteOption().create());
    Map<String, String> parsedArgs = parseArguments(args);
    if (parsedArgs == null) {
      return -1;
    }
    if (hasOption(DefaultOptionCreator.OVERWRITE_OPTION)) {
      HadoopUtil.delete(getConf(), getOutputPath());
      HadoopUtil.delete(getConf(), getTempPath());
    }
    Path labPath;
    String labPathStr = parsedArgs.get("--labelIndex");
    if (labPathStr != null) {
      labPath = new Path(labPathStr);
    } else {
      labPath = getTempPath("labelIndex");
    }
    long labelSize = createLabelIndex(parsedArgs, labPath);
    float alphaI = Float.parseFloat(parsedArgs.get("--alphaI"));
    boolean trainComplementary = Boolean.parseBoolean(parsedArgs.get("--trainComplementary"));


    HadoopUtil.setSerializations(getConf());
    HadoopUtil.cacheFiles(labPath, getConf());

    //add up all the vectors with the same labels, while mapping the labels into our index
    Job indexInstances = prepareJob(getInputPath(), getTempPath(SUMMED_OBSERVATIONS), SequenceFileInputFormat.class,
            IndexInstancesMapper.class, IntWritable.class, VectorWritable.class, VectorSumReducer.class, IntWritable.class,
            VectorWritable.class, SequenceFileOutputFormat.class);
    indexInstances.setCombinerClass(VectorSumReducer.class);
    indexInstances.waitForCompletion(true);
    //sum up all the weights from the previous step, per label and per feature
    Job weightSummer = prepareJob(getTempPath(SUMMED_OBSERVATIONS), getTempPath(WEIGHTS),
            SequenceFileInputFormat.class, WeightsMapper.class, Text.class, VectorWritable.class, VectorSumReducer.class,
            Text.class, VectorWritable.class, SequenceFileOutputFormat.class);
    weightSummer.getConfiguration().set(WeightsMapper.NUM_LABELS, String.valueOf(labelSize));
    weightSummer.setCombinerClass(VectorSumReducer.class);
    weightSummer.waitForCompletion(true);
    //put the per label and per feature vectors into the cache
    HadoopUtil.cacheFiles(getTempPath(WEIGHTS), getConf());
    //calculate the Thetas, write out to LABEL_THETA_NORMALIZER vectors -- TODO: add reference here to the part of the Rennie paper that discusses this
    Job thetaSummer = prepareJob(getTempPath(SUMMED_OBSERVATIONS), getTempPath(THETAS),
            SequenceFileInputFormat.class, ThetaMapper.class, Text.class, VectorWritable.class, VectorSumReducer.class,
            Text.class, VectorWritable.class, SequenceFileOutputFormat.class);
    thetaSummer.setCombinerClass(VectorSumReducer.class);
    thetaSummer.getConfiguration().setFloat(ThetaMapper.ALPHA_I, alphaI);
    thetaSummer.getConfiguration().setBoolean(ThetaMapper.TRAIN_COMPLEMENTARY, trainComplementary);
    thetaSummer.waitForCompletion(true);
    //validate our model and then write it out to the official output
    NaiveBayesModel naiveBayesModel = BayesUtils.readModelFromDir(getTempPath(), getConf());
    naiveBayesModel.validate();
    naiveBayesModel.serialize(getOutputPath(), getConf());

    return 0;
  }

  private long createLabelIndex(Map<String, String> parsedArgs, Path labPath) throws IOException {
    long labelSize = 0;
    if (parsedArgs.containsKey("--labels")) {
      Iterable<String> labels = Splitter.on(",").split(parsedArgs.get("--labels"));
      labelSize = BayesUtils.writeLabelIndex(getConf(), labels, labPath);
    } else if (parsedArgs.containsKey("--extractLabels")) {
      SequenceFileDirIterable<Text, IntWritable> iterable =
              new SequenceFileDirIterable<Text, IntWritable>(getInputPath(), PathType.LIST, PathFilters.logsCRCFilter(), getConf());
      labelSize = BayesUtils.writeLabelIndex(getConf(), labPath, iterable);
    }
    return labelSize;
  }

}
