package cn.ck.utils;

import java.awt.Rectangle;
import ij.process.ImageProcessor;

public class DetectionBox {
        public Rectangle bounds;
        public ImageProcessor mask;
        public int cellSize;
    
        public DetectionBox(Rectangle bounds, ImageProcessor mask, int cellSize) {
            this.bounds = bounds;
            this.mask = mask;
            this.cellSize =cellSize;
    }
}