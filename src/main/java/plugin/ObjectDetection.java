package plugin;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import cn.ck.config.ODConfig;
import cn.ck.utils.ImageJToOpenCV;
import cn.ck.utils.Letterbox;
import ij.process.ImageProcessor;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public class ObjectDetection {
  public String modelPath = null;
  
  private OrtEnvironment env;
  
  private OrtSession session;
  
  public List<float[]> resultBboxes = (List)new ArrayList<>();
  
  public List<Mat> resultCrops = new ArrayList<>();
  
  static {
    OpenCV.loadLocally();
  }
  
  public void init() throws OrtException {
    this.env = OrtEnvironment.getEnvironment();
    OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
    this.session = this.env.createSession(this.modelPath, sessionOptions);
  }
  
  public void YOLO(ImageProcessor ip) throws OrtException {
    float confThreshold = 0.25F;
    float nmsThreshold = 0.3F;
    
    String[] labels = null;
    String meteStr = (String)this.session.getMetadata().getCustomMetadata().get("names");
    labels = new String[(meteStr.split(",")).length];
    Pattern pattern = Pattern.compile("'([^']*)'");
    Matcher matcher = pattern.matcher(meteStr);
    int h = 0;
    while (matcher.find()) {
      labels[h] = matcher.group(1);
      h++;
    } 
    
    Mat img = ImageJToOpenCV.imageProcessorToMat(ip);
    Mat image = img.clone();
    Imgproc.cvtColor(image, image, 4);
    int orig_rows = image.rows();
    int orig_cols = image.cols();
    
    if (orig_cols < 2880);
    Letterbox letterbox = new Letterbox();
    if (orig_cols >= 2880 || orig_rows >= 2880)
      letterbox.setNewShape(new Size(1280.0D, 1280.0D)); 
    image = letterbox.letterbox(image);
    
    double ratio = letterbox.getRatio();
    double dw = letterbox.getDw();
    double dh = letterbox.getDh();
    int rows = letterbox.getHeight().intValue();
    int cols = letterbox.getWidth().intValue();
    int channels = image.channels();
    
    float[] pixels = new float[channels * rows * cols];
    for (int i = 0; i < rows; i++) {
      for (int k = 0; k < cols; k++) {
        double[] pixel = image.get(k, i);
        for (int m = 0; m < channels; m++)
          pixels[rows * cols * m + k * cols + i] = (float)pixel[m] / 255.0F; 
      } 
    } 
    
    long[] shape = { 1L, channels, rows, cols };
    OnnxTensor tensor = OnnxTensor.createTensor(this.env, FloatBuffer.wrap(pixels), shape);
    HashMap<String, OnnxTensor> stringOnnxTensorHashMap = new HashMap<>();
    stringOnnxTensorHashMap.put(this.session.getInputInfo().keySet().iterator().next(), tensor);
    
    OrtSession.Result output = this.session.run(stringOnnxTensorHashMap);
    float[][] outputData = ((float[][][])output.get(0).getValue())[0];
    
    outputData = transposeMatrix(outputData);
    Map<Integer, List<float[]>> class2Bbox = new HashMap<>();
    
    for (float[] bbox : outputData) {
        float[] conditionalProbabilities = Arrays.copyOfRange(bbox, 4, bbox.length);
        int label = argmax(conditionalProbabilities);
        float conf = conditionalProbabilities[label];
        if (conf < confThreshold) continue;

        bbox[4] = conf;

        xywh2xyxy(bbox);
        
        if (bbox[0] >= bbox[2] || bbox[1] >= bbox[3]) continue;
        class2Bbox.putIfAbsent(label, new ArrayList<>());
        class2Bbox.get(label).add(bbox);
    }

    
    List<float[]> bboxes = class2Bbox.get(Integer.valueOf(0));
    if (bboxes == null || bboxes.isEmpty()) {
      this.resultBboxes.clear();
      return;
    } 
    
    bboxes = nonMaxSuppression(bboxes, nmsThreshold);
    this.resultBboxes.clear();
    for (float[] bbox : bboxes) {
      float x1 = (float)((bbox[0] - dw) / ratio);
      float y1 = (float)((bbox[1] - dh) / ratio);
      float x2 = (float)((bbox[2] - dw) / ratio);
      float y2 = (float)((bbox[3] - dh) / ratio);
      x1 = Math.max(0.0F, Math.min(x1, (orig_cols - 1)));
      y1 = Math.max(0.0F, Math.min(y1, (orig_rows - 1)));
      x2 = Math.max(0.0F, Math.min(x2, (orig_cols - 1)));
      y2 = Math.max(0.0F, Math.min(y2, (orig_rows - 1)));
      bbox[0] = x1;
      bbox[1] = y1;
      bbox[2] = x2;
      bbox[3] = y2;
      if (x2 - x1 < 32.0F || y2 - y1 < 32.0F)
        continue; 
      this.resultBboxes.add(bbox);
    } 
  }
  
  public ImageProcessor yoloDraw(ImageProcessor ip) throws OrtException {
    YOLO(ip);
    Mat img = ImageJToOpenCV.imageProcessorToMat(ip);
    int minDwDh = Math.min(img.width(), img.height());
    int thickness = minDwDh / ODConfig.lineThicknessRatio.intValue();
    Random random = new Random();
    Scalar color = new Scalar(255, 0, 0);
    for (float[] bbox : this.resultBboxes) {
      Point topLeft = new Point(bbox[0], bbox[1]);
      Point bottomRight = new Point(bbox[2], bbox[3]);
      Imgproc.rectangle(img, topLeft, bottomRight, color, thickness);
    } 
    ImageProcessor resultIp = ImageJToOpenCV.matToImageProcessor(img);
    return resultIp;
  }
  
  public void yoloCrop(ImageProcessor ip) throws OrtException {
    YOLO(ip);
    Mat img = ImageJToOpenCV.imageProcessorToMat(ip);
    this.resultCrops.clear();
    for (float[] bbox : this.resultBboxes) {
      int x = (int)bbox[0];
      int y = (int)bbox[1];
      int width = (int)(bbox[2] - bbox[0]);
      int height = (int)(bbox[3] - bbox[1]);
      Rect roi = new Rect(x, y, width, height);
      Mat cropped = new Mat(img, roi);
      this.resultCrops.add(cropped);
    } 
  }
  
  public static void scaleCoords(float[] bbox, float orgW, float orgH, float padW, float padH, float gain) {
    bbox[0] = Math.max(0.0F, Math.min(orgW - 1.0F, (bbox[0] - padW) / gain));
    bbox[1] = Math.max(0.0F, Math.min(orgH - 1.0F, (bbox[1] - padH) / gain));
    bbox[2] = Math.max(0.0F, Math.min(orgW - 1.0F, (bbox[2] - padW) / gain));
    bbox[3] = Math.max(0.0F, Math.min(orgH - 1.0F, (bbox[3] - padH) / gain));
  }
  
  public static void xywh2xyxy(float[] bbox) {
    float x = bbox[0];
    float y = bbox[1];
    float w = bbox[2];
    float h = bbox[3];
    bbox[0] = x - w * 0.5F;
    bbox[1] = y - h * 0.5F;
    bbox[2] = x + w * 0.5F;
    bbox[3] = y + h * 0.5F;
  }
  
  public static float[][] transposeMatrix(float[][] m) {
    float[][] temp = new float[(m[0]).length][m.length];
    for (int i = 0; i < m.length; i++) {
      for (int j = 0; j < (m[0]).length; j++)
        temp[j][i] = m[i][j]; 
    } 
    return temp;
  }
  
  public static List<float[]> nonMaxSuppression(List<float[]> bboxes, float iouThreshold) {
    List<float[]> bestBboxes = (List)new ArrayList<>();
    bboxes.sort(Comparator.comparing(a -> Float.valueOf(a[4])));
    while (!bboxes.isEmpty()) {
      float[] bestBbox = bboxes.remove(bboxes.size() - 1);
      bestBboxes.add(bestBbox);
      bboxes = (List<float[]>)bboxes.stream().filter(a -> (computeIOU(a, bestBbox) < iouThreshold)).collect(Collectors.toList());
    } 
    return bestBboxes;
  }
  
  public static float computeIOU(float[] box1, float[] box2) {
    float area1 = (box1[2] - box1[0]) * (box1[3] - box1[1]);
    float area2 = (box2[2] - box2[0]) * (box2[3] - box2[1]);
    float left = Math.max(box1[0], box2[0]);
    float top = Math.max(box1[1], box2[1]);
    float right = Math.min(box1[2], box2[2]);
    float bottom = Math.min(box1[3], box2[3]);
    float interArea = Math.max(right - left, 0.0F) * Math.max(bottom - top, 0.0F);
    float unionArea = area1 + area2 - interArea;
    return Math.max(interArea / unionArea, 1.0E-8F);
  }
  
  public static int argmax(float[] a) {
    float re = -3.4028235E38F;
    int arg = -1;
    for (int i = 0; i < a.length; i++) {
      if (a[i] >= re) {
        re = a[i];
        arg = i;
      } 
    } 
    return arg;
  }
  
  public static Map<String, String> getImagePathMap(String imagePath) {
    Map<String, String> map = new TreeMap<>();
    File file = new File(imagePath);
    if (file.isFile()) {
      map.put(file.getName(), file.getAbsolutePath());
    } else if (file.isDirectory()) {
      byte b;
      int i;
      File[] arrayOfFile;
      for (i = (arrayOfFile = Objects.<File[]>requireNonNull(file.listFiles())).length, b = 0; b < i; ) {
        File tmpFile = arrayOfFile[b];
        map.putAll(getImagePathMap(tmpFile.getPath()));
        b++;
      } 
    } 
    return map;
  }
  
  public List<float[]> getBboxes() {
    return this.resultBboxes;
  }
  
  public List<Mat> getCrops() {
    return this.resultCrops;
  }
}
