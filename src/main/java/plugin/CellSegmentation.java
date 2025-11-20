package plugin;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import cn.ck.config.ODConfig;
import cn.ck.utils.ImageJToOpenCV;
import cn.ck.utils.DetectionBox;
import cn.ck.utils.Logging;
import ij.process.ImageProcessor;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.awt.Rectangle;
import nu.pattern.OpenCV;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public class CellSegmentation {
  public String modelPath = null;
  
  public double threshold = 0.4D;
  
  public List<Number> csvList = new ArrayList<>();
  
  private OrtEnvironment env;
  
  private OrtSession session;
  
  static {
    OpenCV.loadLocally();
  }
  
  public void init() throws OrtException {
    this.env = OrtEnvironment.getEnvironment();
    OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
    this.session = this.env.createSession(this.modelPath, opts);
  }
  
  private Mat preprocess(Mat img) {
    Imgproc.cvtColor(img, img, 4);
    img.convertTo(img, CvType.CV_32FC3, 0.00392156862745098D);
    Core.subtract(img, new Scalar(0.5D, 0.5D, 0.5D), img);
    Core.divide(img, new Scalar(0.5D, 0.5D, 0.5D), img);
    return img;
  }
  
  private OnnxTensor prepareTensor(Mat img) throws OrtException {
    int height = img.rows();
    int width = img.cols();
    int channels = img.channels();
    float[] data = new float[1 * channels * height * width];
    int idx = 0;
    for (int c = 0; c < channels; c++) {
      for (int i = 0; i < height; i++) {
        for (int j = 0; j < width; j++) {
          double[] vals = img.get(i, j);
          data[idx++] = (float)vals[c];
        } 
      } 
    } 
    long[] shape = { 1L, channels, height, width };
    return OnnxTensor.createTensor(this.env, FloatBuffer.wrap(data), shape);
  }
  
  private Mat postprocess(float[][][][] output) {
    int height = (output[0][0]).length;
    int width = (output[0][0][0]).length;
    Mat mask = new Mat(height, width, CvType.CV_8UC1);
    for (int i = 0; i < height; i++) {
      for (int j = 0; j < width; j++) {
        float val = output[0][0][i][j];
        float sigmoid = (float)(1.0D / (1.0D + Math.exp(-val)));
        int pixelVal = (sigmoid > this.threshold) ? 255 : 0;
        mask.put(i, j, new double[] { pixelVal });
      } 
    } 
    return mask;
  }
  
  public Mat infer(Mat image) throws OrtException {
    OnnxTensor inputTensor = prepareTensor(image);
    OrtSession.Result results = this.session.run(Collections.singletonMap(this.session.getInputNames().iterator().next(), inputTensor));
    float[][][][] output = (float[][][][])results.get(0).getValue();
    return postprocess(output);
  }
  
  public static void overlayBlueMask(Mat image, Mat mask, Rect roi, float alpha) {
    if ((mask.size()).width != roi.width || (mask.size()).height != roi.height)
      Imgproc.resize(mask, mask, new Size(roi.width, roi.height)); 
    Mat blue = new Mat(mask.size(), CvType.CV_8UC3, new Scalar(255.0D, 0.0D, 0.0D));
    Mat imageROI = image.submat(roi);
    Mat binaryMask = new Mat();
    Imgproc.threshold(mask, binaryMask, 1.0D, 255.0D, 0);
    Mat blended = new Mat();
    Core.addWeighted(imageROI, 1.0D - alpha, blue, alpha, 0.0D, blended);
    blended.copyTo(imageROI, binaryMask);
  }
  
  public static void putText(Mat image, double labelPercent, int x, int y, Scalar bgColor, Scalar textColor) {
    String label = String.format("%.1f%%", new Object[] { Double.valueOf(labelPercent * 100.0D) });
    int[] baseline = new int[1];
    Size textSize = Imgproc.getTextSize(label, 0, 0.75D, 2, baseline);
    Point bg_tl = new Point(x, y);
    Point bg_br = new Point(x + textSize.width + 4.0D, y + textSize.height + 4.0D);
    Imgproc.rectangle(image, bg_tl, bg_br, bgColor, -1);
    Point textOrg = new Point((x + 2), y + textSize.height + 2.0D);
    Imgproc.putText(image, label, textOrg, 0, 0.75D, textColor, 2);
  }
  
  public void recordCsv(int[] cellPixels, int[] FactinPixels) {
    this.csvList.clear();
    double[] boundaries = { 0.0D, 0.008D, 0.01D, 0.02D, 0.03D, 0.04D, 0.05D, 0.1D, 0.2D, 0.3D, 0.4D, 0.5D, 1.0D };
    int[] count = new int[boundaries.length - 1];
    for (int i = 0; i < cellPixels.length; i++) {
      if (cellPixels[i] != 0) {
        double ratio = (double) FactinPixels[i] / cellPixels[i];
        for (int j = 0; j < boundaries.length - 1; j++) {
          if (ratio >= boundaries[j] && ratio < boundaries[j + 1])
            count[j] = count[j] + 1; 
        } 
      } 
    } 
    int cell = cellPixels.length;
    double FactinRatio = (double)(cell - count[0]) / cell;
    this.csvList.add(Integer.valueOf(cell));
    this.csvList.add(Integer.valueOf(cell - count[0]));
    this.csvList.add(Integer.valueOf(count[0]));
    this.csvList.add(Double.valueOf(FactinRatio));
    for (int k = 1; k < count.length; k++)
      this.csvList.add(Integer.valueOf(count[k])); 
  }
  
  public ImageProcessor unetDraw(ImageProcessor ip, List<Mat> crops, List<float[]> bboxes) throws OrtException {
    if (crops == null || crops.isEmpty() || bboxes == null || bboxes.isEmpty()) {
      Logging.logProgress("   Segmentation: 0/0");
      this.csvList.clear();
      for (int j = 0; j < 11; j++)
        this.csvList.add(Integer.valueOf(0)); 
      return ip;
    } 
    Mat image = ImageJToOpenCV.imageProcessorToMat(ip);
    int minDwDh = Math.min(image.width(), image.height());
    int thickness = minDwDh / ODConfig.lineThicknessRatio.intValue();
    Random random = new Random();
    Scalar color = new Scalar(255, 0, 0);
    Scalar textColor = new Scalar(255.0D, 255.0D, 255.0D);
    for (float[] bbox : bboxes) {
      Point topLeft = new Point(bbox[0], bbox[1]);
      Point bottomRight = new Point(bbox[2], bbox[3]);
      Imgproc.rectangle(image, topLeft, bottomRight, color, thickness);
    } 
    float alpha = 0.5F;
    int[] cellPixels = new int[crops.size()];
    int[] FactinPiexls = new int[crops.size()];
    for (int i = 0; i < crops.size(); i++) {
      int j = i + 1;
      Logging.logProgress("   Segmentation: " + j + "/" + crops.size());
      Mat gray = new Mat();
      Mat binary = new Mat();
      float[] bbox = bboxes.get(i);
      Mat crop = crops.get(i);
      Imgproc.cvtColor(crop.clone(), gray, 6);
      crop = preprocess(crop);
      Mat result = infer(crop);
      int x = (int)bbox[0];
      int y = (int)bbox[1];
      int w = (int)(bbox[2] - bbox[0]);
      int h = (int)(bbox[3] - bbox[1]);
      Rect roi = new Rect(x, y, w, h);
      overlayBlueMask(image, result, roi, alpha);
      
      Scalar meanScalar = Core.mean(gray);
      double meanVal = meanScalar.val[0];
      double threshold = meanVal * 0.8D;
      Imgproc.threshold(gray, binary, threshold, 255.0D, 0);
      cellPixels[i] = Core.countNonZero(binary);
      FactinPiexls[i] = Core.countNonZero(result);
      
      double percent = (double) FactinPiexls[i] / cellPixels[i];
      putText(image, percent, x, y, color, textColor);
    } 
    recordCsv(cellPixels, FactinPiexls);
    ImageProcessor resultIp = ImageJToOpenCV.matToImageProcessor(image);
    return resultIp;
  }
  
  public List<DetectionBox> unetProcess(ImageProcessor ip, List<Mat> crops, List<float[]> bboxes) throws OrtException {
	  List<DetectionBox> DetectionBoxes = new ArrayList<>();
	  if (crops == null || crops.isEmpty() || bboxes == null || bboxes.isEmpty()) {
	      Logging.logProgress("   Segmentation: 0/0");
	      this.csvList.clear();
	      for (int j = 0; j < 11; j++)
	        this.csvList.add(Integer.valueOf(0)); 
	      return DetectionBoxes;
	    } 
	  
	    int[] cellPixels = new int[crops.size()];
	    int[] FactinPiexls = new int[crops.size()];
	    for (int i = 0; i < crops.size(); i++) {
	      int j = i + 1;
	      Logging.logProgress("   Segmentation: " + j + "/" + crops.size());
	      Mat gray = new Mat();
	      Mat binary = new Mat();
	      Mat crop = crops.get(i);
	      float[] bbox = bboxes.get(i);
	      Rectangle rect = new Rectangle(
	    		    (int) bbox[0],
	    		    (int) bbox[1],
	    		    (int) (bbox[2] - bbox[0]),
	    		    (int) (bbox[3] - bbox[1])
	      );
	      
	      Imgproc.cvtColor(crop.clone(), gray, 6);
	      crop = preprocess(crop);
	      Mat result = infer(crop);
	      ImageProcessor resultIp = ImageJToOpenCV.matToImageProcessor(result);
	          
	      Scalar meanScalar = Core.mean(gray);
	      double meanVal = meanScalar.val[0];
	      double threshold = meanVal * 0.8D;
	      Imgproc.threshold(gray, binary, threshold, 255.0D, 0);
	      cellPixels[i] = Core.countNonZero(binary);
	      FactinPiexls[i] = Core.countNonZero(result);
	      
	      DetectionBox DBox = new DetectionBox(rect, resultIp, cellPixels[i]);
	      DetectionBoxes.add(DBox);
	    } 
	    recordCsv(cellPixels, FactinPiexls);
	    return DetectionBoxes;
	  }
}
