package plugin;
import ij.*;
import ij.gui.*;
import ij.plugin.PlugIn;
import ij.process.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * ImageJ插件：实时编辑实例检测掩码
 * 支持画笔绘制和擦除功能来调整预测框内的掩码
 */
public class MaskEditor implements PlugIn, MouseListener, MouseMotionListener, KeyListener {
    
    // 检测框和掩码数据结构
    public static class DetectionBox {
        public Rectangle bounds;
        public ImageProcessor mask;
        public Color boxColor;
        public String label;
        
        public DetectionBox(Rectangle bounds, ImageProcessor mask, Color boxColor, String label) {
            this.bounds = bounds;
            this.mask = mask;
            this.boxColor = boxColor;
            this.label = label;
        }
    }
    
    private ImagePlus imp;
    private ImageProcessor originalProcessor;
    private List<DetectionBox> detectionBoxes;
    private ImageCanvas canvas;
    
    // 绘制工具状态
    private boolean isDrawing = false;
    private boolean isErasing = false;
    private int brushSize = 5;
    private DetectionBox activeBox = null;
    
    // 鼠标轨迹记录（用于解决快速移动断线问题）
    private int lastMouseX = -1;
    private int lastMouseY = -1;
    
    // 原始工具状态（用于恢复）
    private int originalTool;
    private Roi originalRoi;
    private boolean originalMagnifierState;
    
    // UI组件
    private Frame controlFrame;
    private Button drawButton, eraseButton;
    private Scrollbar brushSizeScrollbar;
    private Label statusLabel;
    
    @Override
    public void run(String arg) {
        // 获取当前活动图像
        imp = IJ.getImage();
        if (imp == null) {
            IJ.error("请先打开一张图像");
            return;
        }
        
        originalProcessor = imp.getProcessor().duplicate();
        detectionBoxes = new ArrayList<>();
        
        // 完全禁用ImageJ默认行为
        disableImageJBehaviors();
        
        // 创建示例检测框和掩码（模拟检测结果）
        createSampleDetections();
        
        // 设置鼠标监听器
        canvas = imp.getCanvas();
        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);
        canvas.addKeyListener(this);
        canvas.setFocusable(true);
        canvas.requestFocusInWindow();
        
        // 创建控制面板
        createControlPanel();
        
        // 初始绘制
        updateDisplay();
        
        // 设置默认光标（非编辑状态）
        updateCursor();
        
        IJ.showMessage("掩码编辑器已启动", 
            "使用方法：\n" +
            "1. 点击'绘制模式'开始绘制\n" +
            "2. 点击'擦除模式'进行擦除\n" +
            "3. 滚动条调整画笔大小\n" +
            "4. 在检测框内拖拽鼠标进行编辑\n" +
            "5. 按ESC键退出当前模式\n" +
            "6. 关闭控制面板退出插件");
    }
    
    /**
     * 完全禁用ImageJ的默认鼠标行为
     */
    private void disableImageJBehaviors() {
        // 保存当前状态
        originalTool = Toolbar.getToolId();
        originalRoi = imp.getRoi();
        
        // 清除现有选区
        imp.killRoi();
        
        // 保存并禁用放大镜功能
        ImageWindow win = imp.getWindow();
        if (win != null) {
            ImageCanvas canvas = win.getCanvas();
            // 禁用双击放大功能
            originalMagnifierState = canvas.getMagnification() > 0;
        }
        
        // 设置工具为无效工具并立即应用
        Toolbar toolbar = Toolbar.getInstance();
        if (toolbar != null) {
            // 使用一个不会触发任何默认行为的工具
            toolbar.setTool(Toolbar.SPARE9);
            
            // 强制刷新工具栏状态
            toolbar.repaint();
        }
        
        // 禁用ImageJ的ROI功能
        disableRoiCreation();
    }
    
    /**
     * 禁用ROI创建功能
     */
    private void disableRoiCreation() {
        // 通过设置处理器的ROI创建标志来禁用ROI
        if (imp != null && imp.getCanvas() != null) {
            ImageCanvas canvas = imp.getCanvas();
            
            // 移除所有现有的鼠标监听器（ImageJ默认的）
            MouseListener[] mouseListeners = canvas.getMouseListeners();
            for (MouseListener listener : mouseListeners) {
                if (!listener.equals(this)) {  // 不移除我们自己的监听器
                    canvas.removeMouseListener(listener);
                }
            }
            
            MouseMotionListener[] motionListeners = canvas.getMouseMotionListeners();
            for (MouseMotionListener listener : motionListeners) {
                if (!listener.equals(this)) {  // 不移除我们自己的监听器
                    canvas.removeMouseMotionListener(listener);
                }
            }
        }
    }
    
    /**
     * 创建示例检测结果（模拟实例检测输出）
     */
    private void editMaskWithInterpolation(DetectionBox box, int x, int y, boolean erase) {
        if (lastMouseX != -1 && lastMouseY != -1) {
            int dx = x - lastMouseX;
            int dy = y - lastMouseY;
            int steps = Math.max(Math.abs(dx), Math.abs(dy));
            if (steps == 0) {
                editMask(box, x, y, erase);
            } else {
                for (int i = 0; i <= steps; i++) {
                    int interpX = lastMouseX + dx * i / steps;
                    int interpY = lastMouseY + dy * i / steps;
                    editMask(box, interpX, interpY, erase);
                }
            }
        } else {
            editMask(box, x, y, erase);
        }
        lastMouseX = x;
        lastMouseY = y;
    }

    private void updateButtonStates() {
        if (drawButton != null) {
            drawButton.setBackground(isDrawing ? Color.PINK : null);
        }
        if (eraseButton != null) {
            eraseButton.setBackground(isErasing ? Color.CYAN : null);
        }
    }

    private void createSampleDetections() {
        int width = imp.getWidth();
        int height = imp.getHeight();
        
        // 创建几个示例检测框
        Rectangle box1 = new Rectangle(50, 50, 120, 80);
        Rectangle box2 = new Rectangle(200, 100, 100, 100);
        Rectangle box3 = new Rectangle(100, 200, 150, 120);
        
        // 为每个框创建初始掩码
        detectionBoxes.add(new DetectionBox(box1, createInitialMask(box1), Color.RED, "Person"));
        detectionBoxes.add(new DetectionBox(box2, createInitialMask(box2), Color.GREEN, "Car"));
        detectionBoxes.add(new DetectionBox(box3, createInitialMask(box3), Color.BLUE, "Dog"));
    }
    
    /**
     * 为检测框创建初始椭圆形掩码
     */
    private ImageProcessor createInitialMask(Rectangle bounds) {
        ByteProcessor mask = new ByteProcessor(bounds.width, bounds.height);
        
        // 创建椭圆形掩码作为初始预测结果
        int centerX = bounds.width / 2;
        int centerY = bounds.height / 2;
        int radiusX = bounds.width / 3;
        int radiusY = bounds.height / 3;
        
        for (int y = 0; y < bounds.height; y++) {
            for (int x = 0; x < bounds.width; x++) {
                double dx = (x - centerX) / (double) radiusX;
                double dy = (y - centerY) / (double) radiusY;
                if (dx * dx + dy * dy <= 1.0) {
                    mask.putPixel(x, y, 255);
                }
            }
        }
        
        return mask;
    }
    
    /**
     * 创建控制面板
     */
    private void createControlPanel() {
        controlFrame = new Frame("掩码编辑控制面板");
        controlFrame.setLayout(new GridLayout(6, 1, 5, 5));
        
        // 绘制模式按钮
        drawButton = new Button("绘制模式");
        drawButton.addActionListener(e -> {
            isDrawing = true;
            isErasing = false;
            updateButtonStates();
            updateCursor();
            statusLabel.setText("状态: 绘制模式 - 在框内拖拽鼠标绘制掩码");
            // 确保焦点在canvas上
            canvas.requestFocusInWindow();
        });
        
        // 擦除模式按钮
        eraseButton = new Button("擦除模式");
        eraseButton.addActionListener(e -> {
            isDrawing = false;
            isErasing = true;
            updateButtonStates();
            updateCursor();
            statusLabel.setText("状态: 擦除模式 - 在框内拖拽鼠标擦除掩码");
            // 确保焦点在canvas上
            canvas.requestFocusInWindow();
        });
        
        // 画笔大小控制
        Label brushLabel = new Label("画笔大小: " + brushSize);
        brushSizeScrollbar = new Scrollbar(Scrollbar.HORIZONTAL, brushSize, 1, 1, 21);
        brushSizeScrollbar.addAdjustmentListener(e -> {
            brushSize = brushSizeScrollbar.getValue();
            brushLabel.setText("画笔大小: " + brushSize);
            updateCursor(); // 更新光标大小
        });
        
        // 状态标签
        statusLabel = new Label("状态: 准备就绪 - 选择绘制或擦除模式");
        
        // 重置按钮
        Button resetButton = new Button("重置所有掩码");
        resetButton.addActionListener(e -> {
            for (DetectionBox box : detectionBoxes) {
                box.mask = createInitialMask(box.bounds);
            }
            updateDisplay();
            statusLabel.setText("状态: 已重置所有掩码");
        });
        
        controlFrame.add(drawButton);
        controlFrame.add(eraseButton);
        controlFrame.add(brushLabel);
        controlFrame.add(brushSizeScrollbar);
        controlFrame.add(resetButton);
        controlFrame.add(statusLabel);
        
        controlFrame.pack();
        controlFrame.setVisible(true);
        
        // 窗口关闭事件
        controlFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cleanup();
            }
        });
    }
    
    /**
     * 更新鼠标光标为画笔大小的圆点
     */
    private void updateCursor() {
        if (canvas == null) return;
        
        if (isDrawing || isErasing) {
            // 创建自定义光标 - 圆形画笔指示器
            int cursorSize = Math.max(32, Math.min(64, brushSize * 4 + 16));
            BufferedImage cursorImage = new BufferedImage(cursorSize, cursorSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = cursorImage.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // 清除背景（完全透明）
            g2d.setComposite(AlphaComposite.Clear);
            g2d.fillRect(0, 0, cursorSize, cursorSize);
            g2d.setComposite(AlphaComposite.SrcOver);
            
            int centerX = cursorSize / 2;
            int centerY = cursorSize / 2;
            
            // 绘制画笔大小指示圆圈（外圆）
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.drawOval(centerX - brushSize, centerY - brushSize, brushSize * 2, brushSize * 2);
            
            // 绘制内部填充（半透明）
            if (isDrawing) {
                g2d.setColor(new Color(255, 0, 0, 80)); // 半透明红色
            } else {
                g2d.setColor(new Color(0, 100, 255, 80)); // 半透明蓝色
            }
            g2d.fillOval(centerX - brushSize + 1, centerY - brushSize + 1, 
                        brushSize * 2 - 2, brushSize * 2 - 2);
            
            // 绘制中心十字准星
            g2d.setColor(Color.BLACK);
            g2d.setStroke(new BasicStroke(1));
            int crossSize = 4;
            g2d.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY);
            g2d.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize);
            
            // 添加外圈高亮（增强可见性）
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.drawOval(centerX - brushSize - 1, centerY - brushSize - 1, 
                        brushSize * 2 + 2, brushSize * 2 + 2);
            
            g2d.dispose();
            
            // 创建自定义光标
            Point hotspot = new Point(centerX, centerY);
            String cursorName = isDrawing ? "DrawBrush_" + brushSize : "EraseBrush_" + brushSize;
            
            try {
                Cursor customCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                    cursorImage, hotspot, cursorName);
                canvas.setCursor(customCursor);
            } catch (Exception ex) {
                // 如果创建自定义光标失败，使用十字光标
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }
            
        } else {
            // 非编辑状态使用默认光标
            canvas.setCursor(Cursor.getDefaultCursor());
        }
    }
    
    /**
     * 更新图像显示
     */
    private void updateDisplay() {
        ImageProcessor displayProcessor = originalProcessor.duplicate();
        
        // 绘制每个检测框和掩码
        for (DetectionBox box : detectionBoxes) {
            // 绘制掩码（半透明覆盖）
            drawMaskOverlay(displayProcessor, box);
            
            // 绘制检测框
            displayProcessor.setColor(box.boxColor);
            displayProcessor.setLineWidth(2);
            displayProcessor.drawRect(box.bounds.x, box.bounds.y, box.bounds.width, box.bounds.height);
            
            // 绘制标签背景
            displayProcessor.setColor(Color.WHITE);
            displayProcessor.fillRect(box.bounds.x, box.bounds.y - 20, 60, 20);
            
            // 绘制标签文字
            displayProcessor.setColor(box.boxColor);
            displayProcessor.drawString(box.label, box.bounds.x + 2, box.bounds.y - 5);
        }
        
        imp.setProcessor(displayProcessor);
        imp.updateAndDraw();
    }
    
    /**
     * 在图像上绘制掩码覆盖层
     */
    private void drawMaskOverlay(ImageProcessor processor, DetectionBox box) {
        // 创建半透明掩码覆盖
        for (int y = 0; y < box.bounds.height; y++) {
            for (int x = 0; x < box.bounds.width; x++) {
                int maskValue = box.mask.getPixel(x, y);
                if (maskValue > 0) {
                    int imgX = box.bounds.x + x;
                    int imgY = box.bounds.y + y;
                    if (imgX >= 0 && imgX < processor.getWidth() && 
                        imgY >= 0 && imgY < processor.getHeight()) {
                        
                        // 获取原始像素值
                        int originalPixel = processor.getPixel(imgX, imgY);
                        
                        // 混合颜色（半透明效果）
                        Color maskColor = box.boxColor;
                        int r = (originalPixel & 0xff0000) >> 16;
                        int g = (originalPixel & 0x00ff00) >> 8;
                        int b = originalPixel & 0x0000ff;
                        
                        r = (r + maskColor.getRed()) / 2;
                        g = (g + maskColor.getGreen()) / 2;
                        b = (b + maskColor.getBlue()) / 2;
                        
                        int blendedPixel = (r << 16) | (g << 8) | b;
                        processor.putPixel(imgX, imgY, blendedPixel);
                    }
                }
            }
        }
    }
    
    /**
     * 查找鼠标位置对应的检测框
     */
    private DetectionBox findBoxAtPosition(int x, int y) {
        for (DetectionBox box : detectionBoxes) {
            if (box.bounds.contains(x, y)) {
                return box;
            }
        }
        return null;
    }
    
    /**
     * 在掩码上绘制或擦除
     */
    private void editMask(DetectionBox box, int x, int y, boolean erase) {
        if (box == null) return;
        
        // 转换为掩码坐标系
        int maskX = x - box.bounds.x;
        int maskY = y - box.bounds.y;
        
        // 绘制圆形画笔
        for (int dy = -brushSize; dy <= brushSize; dy++) {
            for (int dx = -brushSize; dx <= brushSize; dx++) {
                if (dx * dx + dy * dy <= brushSize * brushSize) {
                    int px = maskX + dx;
                    int py = maskY + dy;
                    
                    if (px >= 0 && px < box.mask.getWidth() && 
                        py >= 0 && py < box.mask.getHeight()) {
                        box.mask.putPixel(px, py, erase ? 0 : 255);
                    }
                }
            }
        }
    }
    
    // 鼠标事件处理 - 完全覆盖ImageJ默认行为
    @Override
    public void mousePressed(MouseEvent e) {
        if (!isDrawing && !isErasing) {
            // 非编辑模式下也要阻止默认行为
            e.consume();
            return;
        }
        
        int x = canvas.offScreenX(e.getX());
        int y = canvas.offScreenY(e.getY());
        
        activeBox = findBoxAtPosition(x, y);
        if (activeBox != null) {
            // 重置轨迹记录
            lastMouseX = -1;
            lastMouseY = -1;
            
            editMaskWithInterpolation(activeBox, x, y, isErasing);
            updateDisplay();
        }
        
        // 阻止事件进一步传播到ImageJ
        e.consume();
    }
    
    @Override
    public void mouseDragged(MouseEvent e) {
        if (activeBox == null || (!isDrawing && !isErasing)) {
            e.consume();
            return;
        }
        
        int x = canvas.offScreenX(e.getX());
        int y = canvas.offScreenY(e.getY());
        
        if (activeBox.bounds.contains(x, y)) {
            editMaskWithInterpolation(activeBox, x, y, isErasing);
            updateDisplay();
        }
        
        // 阻止事件进一步传播到ImageJ
        e.consume();
    }
    
    @Override
    public void mouseReleased(MouseEvent e) {
        activeBox = null;
        // 重置轨迹记录
        lastMouseX = -1;
        lastMouseY = -1;
        
        // 阻止事件进一步传播到ImageJ
        e.consume();
    }
    
    @Override
    public void mouseMoved(MouseEvent e) {
        // 重置轨迹记录（鼠标移动时不绘制）
        lastMouseX = -1;
        lastMouseY = -1;
        
        // 阻止事件进一步传播到ImageJ
        e.consume();
    }
    
    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            isDrawing = false;
            isErasing = false;
            updateButtonStates();
            updateCursor();
            statusLabel.setText("状态: 已取消编辑模式");
        }
        
        // 阻止事件进一步传播到ImageJ
        e.consume();
    }
    
    // 未使用的事件方法 - 都要阻止默认行为
    @Override public void mouseClicked(MouseEvent e) { e.consume(); }
    @Override public void mouseEntered(MouseEvent e) { e.consume(); }
    @Override public void mouseExited(MouseEvent e) { e.consume(); }
    @Override public void keyReleased(KeyEvent e) { e.consume(); }
    @Override public void keyTyped(KeyEvent e) { e.consume(); }
    
    /**
     * 清理资源并恢复ImageJ默认行为
     */
    private void cleanup() {
        if (canvas != null) {
            canvas.removeMouseListener(this);
            canvas.removeMouseMotionListener(this);
            canvas.removeKeyListener(this);
            // 恢复默认光标
            canvas.setCursor(Cursor.getDefaultCursor());
        }
        
        // 恢复ImageJ默认行为
        restoreImageJBehaviors();
        
        if (controlFrame != null) {
            controlFrame.dispose();
        }
        
        IJ.showStatus("掩码编辑器已关闭，ImageJ默认功能已恢复");
    }
    
    /**
     * 恢复ImageJ的默认行为
     */
    private void restoreImageJBehaviors() {
        // 恢复原始工具
        if (originalTool >= 0) {
            Toolbar.getInstance().setTool(originalTool);
        }
        
        // 恢复原始ROI
        if (originalRoi != null) {
            imp.setRoi(originalRoi);
        }
        
        // 重新添加ImageJ默认的鼠标监听器
        if (imp != null && imp.getWindow() != null) {
            ImageCanvas canvas = imp.getCanvas();
            if (canvas != null) {
                // ImageJ会在需要时自动重新添加其默认监听器
                canvas.repaint();
            }
        }
    }
    
    /**
     * 主函数 - 用于测试
     */
    public static void main(String[] args) {
        // 启动ImageJ
        new ImageJ();
        
        // 等待ImageJ完全加载
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        // 创建测试图像
        ImagePlus testImage = createTestImage();
        testImage.show();
        
        // 等待图像显示完成
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        // 启动插件
        MaskEditor plugin = new MaskEditor();
        plugin.run("");
        
        System.out.println("掩码编辑插件已启动！");
        System.out.println("操作说明：");
        System.out.println("1. 在控制面板中选择'绘制模式'或'擦除模式'");
        System.out.println("2. 调整画笔大小滑动条");
        System.out.println("3. 在彩色检测框内拖拽鼠标进行编辑");
        System.out.println("4. 按ESC键退出当前编辑模式");
    }
    
    /**
     * 创建测试图像
     */
    private static ImagePlus createTestImage() {
        int width = 400;
        int height = 400;
        
        // 创建彩色测试图像
        ColorProcessor cp = new ColorProcessor(width, height);
        
        // 填充渐变背景
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255) / width;
                int g = (y * 255) / height;
                int b = 128;
                cp.putPixel(x, y, (r << 16) | (g << 8) | b);
            }
        }
        
        // 使用ImageProcessor绘制方法添加图案
        // 绘制几个椭圆作为"物体"
        cp.setColor(Color.YELLOW);
        cp.fillOval(70, 70, 80, 60);
        
        cp.setColor(Color.CYAN);
        cp.fillOval(220, 120, 80, 80);
        
        cp.setColor(Color.MAGENTA);
        cp.fillOval(120, 220, 120, 100);
        
        return new ImagePlus("测试图像 - 实例检测掩码编辑", cp);
    }
}