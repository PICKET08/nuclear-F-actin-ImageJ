package cn.ck.utils;

import ij.IJ;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ij.process.ByteProcessor;

public class processorGreyToRGB {
	public static ColorProcessor convertToRGB(ImageProcessor ip, int channelIndex) {
        if (ip == null)
            throw new IllegalArgumentException("Input cannot be null");
        if (channelIndex < 0 || channelIndex > 2)
            throw new IllegalArgumentException("Channel index must be 0(R), 1(G), or 2(B)");

        if (ip instanceof ShortProcessor) {
            ip = ((ShortProcessor) ip).convertToByte(true); 
        } else if (ip instanceof FloatProcessor) {
            ip = ((FloatProcessor) ip).convertToByte(true); 
        } else if (!(ip instanceof ByteProcessor)) {
            throw new IllegalArgumentException("Unsupported processor type");
        }

        int width = ip.getWidth();
        int height = ip.getHeight();
        ColorProcessor cp = new ColorProcessor(width, height);
        int[] rgbPixels = (int[]) cp.getPixels();
        byte[] pixels = (byte[]) ((ByteProcessor) ip).getPixels();
 
        
//        int min = 255, max = 0;
//        for (byte b : pixels) {
//            int val = b & 0xFF;
//            if (val < min) min = val;
//            if (val > max) max = val;
//        }
        
        for (int i = 0; i < pixels.length; i++) {
            int value = pixels[i] & 0xFF; 
            // int norm = (int) ((value - min) / (double)(max - min) * 255);
            int rgb = 0;
            switch (channelIndex) {
                case 0: rgb = value << 16; break; 
                case 1: rgb = value << 8;  break; 
                case 2: rgb = value;       break; 
            }
            rgbPixels[i] = rgb;
        }

        return cp;
    }
  
}
