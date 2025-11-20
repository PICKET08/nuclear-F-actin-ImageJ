package cn.ck.utils;

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import nu.pattern.OpenCV;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

public class ImageJToOpenCV {
  static {
    OpenCV.loadLocally();
  }
  
  public static Mat imageProcessorToMat(ImageProcessor ip) {
    int width = ip.getWidth();
    int height = ip.getHeight();
    int type = ip.getBitDepth();
    if (type == 8) {
      byte[] pixels = (byte[])ip.getPixels();
      Mat mat = new Mat(height, width, CvType.CV_8UC1);
      mat.put(0, 0, pixels);
      return mat;
    } 
    if (type == 16) {
      short[] pixels = (short[])ip.getPixels();
      Mat mat = new Mat(height, width, CvType.CV_16UC1);
      byte[] bytes = new byte[pixels.length * 2];
      for (int i = 0; i < pixels.length; i++) {
        bytes[i * 2] = (byte)(pixels[i] & 0xFF);
        bytes[i * 2 + 1] = (byte)(pixels[i] >> 8 & 0xFF);
      } 
      mat.put(0, 0, bytes);
      return mat;
    } 
    if (type == 24 || ip instanceof ColorProcessor) {
      int[] pixels = (int[])ip.getPixels();
      Mat mat = new Mat(height, width, CvType.CV_8UC3);
      byte[] data = new byte[width * height * 3];
      for (int i = 0; i < pixels.length; i++) {
        int c = pixels[i];
        byte r = (byte)(c >> 16 & 0xFF);
        byte g = (byte)(c >> 8 & 0xFF);
        byte b = (byte)(c & 0xFF);
        data[i * 3] = b;
        data[i * 3 + 1] = g;
        data[i * 3 + 2] = r;
      } 
      mat.put(0, 0, data);
      return mat;
    } 
    throw new IllegalArgumentException("Unsupported image type or bit depth: " + type);
  }
  
  public static ImageProcessor matToImageProcessor(Mat mat) {
    int width = mat.cols();
    int height = mat.rows();
    int type = mat.type();
    if (type == CvType.CV_8UC1) {
      byte[] pixels = new byte[width * height];
      mat.get(0, 0, pixels);
      return (ImageProcessor)new ByteProcessor(width, height, pixels);
    } 
    if (type == CvType.CV_16UC1) {
      short[] pixels = new short[width * height];
      mat.get(0, 0, pixels);
      return (ImageProcessor)new ShortProcessor(width, height, pixels, null);
    } 
    if (type == CvType.CV_8UC3) {
      byte[] pixels = new byte[width * height * 3];
      mat.get(0, 0, pixels);
      int[] imageJPixels = new int[width * height];
      for (int i = 0; i < width * height; i++) {
        int b = pixels[i * 3] & 0xFF;
        int g = pixels[i * 3 + 1] & 0xFF;
        int r = pixels[i * 3 + 2] & 0xFF;
        int argb = 0xFF000000 | r << 16 | g << 8 | b;
        imageJPixels[i] = argb;
      } 
      return (ImageProcessor)new ColorProcessor(width, height, imageJPixels);
    } 
    throw new IllegalArgumentException("Unsupported Mat type: " + type);
  }
}
