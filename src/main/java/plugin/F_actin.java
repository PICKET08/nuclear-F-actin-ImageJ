package plugin;

import ai.onnxruntime.OrtException;
import cn.ck.utils.CsvLogger;
import cn.ck.utils.Logging;
import cn.ck.utils.DetectionBox;
import cn.ck.utils.processorGreyToRGB;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.plugin.filter.PlugInFilter;
import ij.process.*;
import ij.io.FileSaver;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Date;
import java.text.SimpleDateFormat;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.border.BevelBorder;

public class F_actin implements PlugInFilter, MouseListener, MouseMotionListener, KeyListener{
  private int Mode = 1;
  
  private int Multiple = 0;
  
  private String detSelectedModel = null;
  
  private String segSelectedModel = null;
  
  private ObjectDetection detector = new ObjectDetection();
  
  private CellSegmentation segmentor = new CellSegmentation();
  
  private int Magnification = 100;
  private int BrushSize = 3;
  
  private boolean isDrawing = false;
  private boolean isErasing = false;
  private boolean isAdding = false;
  private boolean isDeleting = false;
  
  private int lastMouseX = -1;
  private int lastMouseY = -1;
  
  private List<DetectionBox> DetectionBoxes, tempDetectionBoxes;

  private ImagePlus imp;
  private ImageCanvas canvas;
  private ImageProcessor originalProcessor;

  private int addStartX, addStartY;
  private DetectionBox addBox = null;
  private DetectionBox activeBox = null;
  
  private int originalTool;
  private Roi originalRoi;
  private boolean originalMagnifierState;
  
  //private String baseModelFolder = String.valueOf(IJ.getDirectory("imagej")) + File.separator;
  private String baseModelFolder = "C:/Users/Administrator/Desktop/ImageJ/";
  
  public int setup(String arg, ImagePlus imp) {
    if (arg.equals("about"))
    	return DONE;
    
    return DOES_8G | DOES_16 | DOES_32 | DOES_RGB;
  }
  
  public void run(ImageProcessor ip) {
    showDialog();
  }
  
  private void disableImageJBehaviors() {
      originalTool = Toolbar.getToolId();
      originalRoi = imp.getRoi();
      
      imp.killRoi();
      
      ImageWindow win = imp.getWindow();
      if (win != null) {
          ImageCanvas canvas = win.getCanvas();
          originalMagnifierState = canvas.getMagnification() > 0;
      }
      
      Toolbar toolbar = Toolbar.getInstance();
      if (toolbar != null) {
          toolbar.setTool(Toolbar.SPARE9);
          
          toolbar.repaint();
      }
      
      disableRoiCreation();
  }
  

  private void disableRoiCreation() {
      if (imp != null && imp.getCanvas() != null) {
          ImageCanvas canvas = imp.getCanvas();
          
          MouseListener[] mouseListeners = canvas.getMouseListeners();
          for (MouseListener listener : mouseListeners) {
              if (!listener.equals(this)) {  
                  canvas.removeMouseListener(listener);
              }
          }
          
          MouseMotionListener[] motionListeners = canvas.getMouseMotionListeners();
          for (MouseMotionListener listener : motionListeners) {
              if (!listener.equals(this)) {  
                  canvas.removeMouseMotionListener(listener);
              }
          }
      }
  }
  
  private void setActiveImage(ImagePlus activeImage) {
	    if (canvas != null) {
	        canvas.removeMouseListener(this);
	        canvas.removeMouseMotionListener(this);
	        canvas.removeKeyListener(this);
	    }
	    
	    imp = activeImage;
	    canvas = imp.getCanvas();
	    canvas.addMouseListener(this);
	    canvas.addMouseMotionListener(this);
	    canvas.addKeyListener(this);
	    canvas.setFocusable(true);
	    canvas.requestFocusInWindow();
	    
	    disableImageJBehaviors();
  }
  
  private void createLabel(JDialog dialog) {
	  
    JLabel detModelLabel = new JLabel("Det Model");
    detModelLabel.setBounds(35, 30, 80, 25);
    detModelLabel.setHorizontalAlignment(JLabel.RIGHT);
    dialog.add(detModelLabel);
    
    JLabel segModelLabel = new JLabel("Seg Model");
    segModelLabel.setBounds(35, 60, 80, 25);
    segModelLabel.setHorizontalAlignment(JLabel.RIGHT);
    dialog.add(segModelLabel);
    
    JLabel magLabel = new JLabel("Magnification");
    magLabel.setBounds(35, 90, 80, 25);
    magLabel.setHorizontalAlignment(JLabel.RIGHT);
    dialog.add(magLabel);
    
    JLabel processLabel = new JLabel("PostProcessing");
    processLabel.setBounds(35, 120, 80, 25);
    processLabel.setHorizontalAlignment(JLabel.RIGHT);
    dialog.add(processLabel);
    
    JLabel taskLabel = new JLabel("Input Mode");
    taskLabel.setBounds(35, 150, 80, 25);
    taskLabel.setHorizontalAlignment(JLabel.RIGHT);
    dialog.add(taskLabel);
    
    JLabel modeLabel = new JLabel("Process Mode");
    modeLabel.setBounds(35, 180, 80, 25);
    modeLabel.setHorizontalAlignment(JLabel.RIGHT);
    dialog.add(modeLabel);
    
  }
  
  private void createCombobox(String baseModelFolder, JDialog dialog) {
	  
    String detModelsPath = String.valueOf(baseModelFolder) + "models" + File.separator + "det" + File.separator;
    String[] detOptions = getModels(detModelsPath, "Select Det Model");
    JComboBox<String> detComboBox = new JComboBox<>(detOptions);
    detComboBox.setBounds(125, 30, 220, 25);
    dialog.add(detComboBox);
    
    detComboBox.addActionListener(e -> {
          String selected = (String)detComboBox.getSelectedItem();
          if (selected != null && selected.toLowerCase().endsWith(".onnx")) {
            detector.modelPath = detModelsPath + selected;
            try {
              this.detector.init();
            } catch (OrtException ortException) {}
            Logging.log("Detection model selected: " + selected);
          } 
        });
    
    String segModelsPath = String.valueOf(baseModelFolder) + "models" + File.separator + "seg" + File.separator;
    String[] segOptions = getModels(segModelsPath, "Select Seg Model");
    JComboBox<String> segComboBox = new JComboBox<>(segOptions);
    segComboBox.setBounds(125, 60, 220, 25);
    dialog.add(segComboBox);
    
    segComboBox.addActionListener(e -> {
          String selected = (String)segComboBox.getSelectedItem();
          if (selected != null && selected.toLowerCase().endsWith(".onnx")) {
            segmentor.modelPath = segModelsPath + selected;
            try {
              this.segmentor.init();
            } catch (OrtException ortException) {}
            Logging.log("Segmentation model selected: " + selected);
          } 
        });
  }
  
  private void createModeRatio(JDialog dialog) {
    JRadioButton radioDS = new JRadioButton("Det and Seg");
    radioDS.setBounds(125, 180, 110, 25);
    radioDS.setSelected(true);
    
    JRadioButton radioOD = new JRadioButton("Only Det");
    radioOD.setBounds(235, 180, 110, 25);
    
    ButtonGroup radiogroup = new ButtonGroup();
    radiogroup.add(radioDS);
    radiogroup.add(radioOD);
    
    dialog.add(radioDS);
    dialog.add(radioOD);
    
    radioDS.addActionListener(e -> {
          if (radioDS.isSelected())
            this.Mode = 1; 
        });
    
    radioOD.addActionListener(e -> {
          if (radioOD.isSelected())
            this.Mode = 0; 
        });
  }
  
  private void createMultipleRatio(JDialog dialog) {
    JRadioButton radioM = new JRadioButton("Multiple");
    radioM.setBounds(235, 150, 110, 25);
    
    JRadioButton radioS = new JRadioButton("Single");
    radioS.setBounds(125, 150, 110, 25);
    radioS.setSelected(true);
    
    ButtonGroup radiogroup = new ButtonGroup();
    radiogroup.add(radioM);
    radiogroup.add(radioS);
    
    dialog.add(radioM);
    dialog.add(radioS);
    
    radioM.addActionListener(e -> {
          if (radioM.isSelected())
            this.Multiple = 1; 
        });
    
    radioS.addActionListener(e -> {
          if (radioS.isSelected())
            this.Multiple = 0; 
        });
  }
  
  private void createThresSpinner(JDialog dialog) {
    SpinnerNumberModel spinnerModel = new SpinnerNumberModel(this.segmentor.threshold, 0.0D, 1.0D, 0.1D);
    JSpinner thresholdSpinner = new JSpinner(spinnerModel);
    thresholdSpinner.setBounds(125, 120, 220, 25);
    dialog.add(thresholdSpinner);
    JComponent editor = thresholdSpinner.getEditor();
    JFormattedTextField tf = ((JSpinner.DefaultEditor)editor).getTextField();
    tf.setBackground(Color.WHITE);
    tf.setHorizontalAlignment(0);
    thresholdSpinner.addChangeListener(e -> this.segmentor.threshold = ((Double)thresholdSpinner.getValue()).doubleValue());
  }
  
  private void createMagSpinner(JDialog dialog) {
    SpinnerNumberModel magnificationModel = new SpinnerNumberModel(this.Magnification, 10, 500, 10);
    JSpinner magnificationSpinner = new JSpinner(magnificationModel);
    magnificationSpinner.setBounds(125, 90, 220, 25);
    dialog.add(magnificationSpinner);
    JComponent editor = magnificationSpinner.getEditor();
    JFormattedTextField tf = ((JSpinner.DefaultEditor)editor).getTextField();
    tf.setBackground(Color.WHITE);
    tf.setHorizontalAlignment(0);
    magnificationSpinner.addChangeListener(e -> this.Magnification = ((Integer)magnificationSpinner.getValue()).intValue());
  }
  
  private void createLogging(JDialog dialog) {
    JTextPane logTextPane = new JTextPane();
    logTextPane.setEditable(false);
    JScrollPane scrollPane = new JScrollPane(logTextPane);
    scrollPane.setBounds(35, 325, 370, 230);
    scrollPane.setVerticalScrollBarPolicy(22);
    dialog.add(scrollPane);
    Logging.setLogArea(logTextPane);
  }
  
  private void createControlPanel(JDialog dialog) {
	  
	  JPanel editPanel = new JPanel();
	  editPanel.setBounds(35, 215, 370, 100);
	  editPanel.setBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED));
	  editPanel.setLayout(null);
	  dialog.add(editPanel);
	  
	  JLabel boxEditLabel = new JLabel("Box Edit");
	  boxEditLabel.setBounds(0, 2, 70, 25);
	  boxEditLabel.setHorizontalAlignment(JLabel.CENTER);

	  JRadioButton radioAdd = new JRadioButton("Add");
	  radioAdd.setBounds(80, 2, 60, 25);
	  
	  radioAdd.addActionListener(e -> {
            if (radioAdd.isSelected())
              isDrawing = false;
              isErasing = false;
              isAdding = true;
              isDeleting = false;
      });
		
	  JRadioButton radioDel = new JRadioButton("Delete");
	  radioDel.setBounds(150, 2, 60, 25);
	  
	  radioDel.addActionListener(e -> {
          if (radioDel.isSelected())
            isDrawing = false;
            isErasing = false;
            isAdding = false;
            isDeleting = true;
      });
	  
	  JLabel maskEditLabel = new JLabel("Mask Edit");
	  maskEditLabel.setBounds(0, 37, 70, 25);
	  maskEditLabel.setHorizontalAlignment(JLabel.CENTER);

	  JRadioButton radioDra = new JRadioButton("Draw");
      radioDra.setBounds(80, 37, 60, 25);
      
      radioDra.addActionListener(e -> {
          if (radioDra.isSelected())
            isDrawing = true;
            isErasing = false;
            isAdding = false;
            isDeleting = false;
            updateCursor();
      });
	  
	  JRadioButton radioEra = new JRadioButton("Erase");
	  radioEra.setBounds(150, 37, 60, 25);
	  
	  radioEra.addActionListener(e -> {
          if (radioEra.isSelected())
            isDrawing = false;
            isErasing = true;
            isAdding = false;
            isDeleting = false;
            updateCursor();
      });
	  
	  ButtonGroup radioGroupM = new ButtonGroup();
	  radioGroupM.add(radioDra);
	  radioGroupM.add(radioEra);
	  radioGroupM.add(radioAdd);
	  radioGroupM.add(radioDel);
	  
	  JLabel brushLabel = new JLabel("BrushSize");
	  brushLabel.setBounds(220, 37, 60, 25);
	  brushLabel.setHorizontalAlignment(JLabel.CENTER);
	  
	  SpinnerNumberModel brushModel = new SpinnerNumberModel(this.BrushSize, 1, 10, 1);
	  JSpinner brushSpinner = new JSpinner(brushModel);
	  brushSpinner.setBounds(290, 37, 70, 25);
	  JComponent editor = brushSpinner.getEditor();
	  JFormattedTextField tf = ((JSpinner.DefaultEditor)editor).getTextField();
	  tf.setBackground(Color.WHITE);
	  tf.setHorizontalAlignment(0);
	  brushSpinner.addChangeListener(e -> this.BrushSize = (int)brushSpinner.getValue());
	  
	  
//	  JButton updateBtn = new JButton("Update");
//	  updateBtn.setBounds(100, 72, 80, 22);
//	  
//	  JButton resetBtn = new JButton("Reset");
//	  resetBtn.setBounds(190, 72, 80, 22);
	  
	  JButton saveBtn = new JButton("Save");
	  saveBtn.setBounds(260, 72, 100, 22);
	  
	  saveBtn.addActionListener(e -> {
		  saveBtn.setText("Saving...");
		  saveBtn.setEnabled(false);
		  String path = saveResults() ;
		  Logging.log("\nSaved in:\n"+ path);
		  saveBtn.setText("Save");
		  saveBtn.setEnabled(true);
      });
 
	  editPanel.add(boxEditLabel);
	  editPanel.add(radioAdd);
	  editPanel.add(radioDel);
	  editPanel.add(maskEditLabel);
	  editPanel.add(radioDra);
	  editPanel.add(radioEra);
	  editPanel.add(brushSpinner);
	  editPanel.add(brushLabel);
//	   editPanel.add(updateBtn);
//	   editPanel.add(resetBtn);
	  editPanel.add(saveBtn);
  
  }
  
  private void showDialog() {
    final CsvLogger csv;
    
    try {
      UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsClassicLookAndFeel");
      Font font = new Font("Segoe UI", 0, 12);
      Enumeration<Object> keys = UIManager.getDefaults().keys();
      
      while (keys.hasMoreElements()) {
        Object key = keys.nextElement();
        Object value = UIManager.get(key);
        if (value instanceof Font)
          UIManager.put(key, font); 
      } 
      
    } catch (Exception e_w) {
      e_w.printStackTrace();
    } 
    
    List<String> headers = Arrays.asList(new String[] { 
          "Image", "Cell", "F-actin", "Normal", "Ratio", 
          "[0.8%-1%)", "[1%-2%)",
          "[2%-3%)",   "[3%-4%)", "[4%-5%)",
          "[5%-10%)",  "[10%-20%)", 
          "[20%-30%)", "[30%-40%)", 
          "[40%-50%)", "[50%-100%]" });
    
    try {
      csv = new CsvLogger(baseModelFolder, headers);
    } catch (IOException e_f) {
      throw new RuntimeException(e_f);
    } 
    
    JDialog dialog = new JDialog();
    dialog.setTitle("nuclear F-actin");
    dialog.setModal(false);
    dialog.setSize(450, 635);
    dialog.setLayout((LayoutManager)null);
    dialog.setDefaultCloseOperation(2);
    
    dialog.addWindowListener(new WindowAdapter() {
          public void windowClosed(WindowEvent e) {
            try {
              csv.close();
            } catch (IOException e_f) {
              throw new RuntimeException(e_f);
            } 
          }
        });
    
    createLabel(dialog);
    createCombobox(baseModelFolder, dialog);
    createModeRatio(dialog);
    createMultipleRatio(dialog);
    createThresSpinner(dialog);
    createMagSpinner(dialog);
    createLogging(dialog);
    createControlPanel(dialog);
    
    JButton runBtn = new JButton("Run");
    runBtn.setBounds(220, 565, 100, 20);
    runBtn.addActionListener(e -> {
          ImagePlus currentImage = WindowManager.getCurrentImage();
          
          if (currentImage == null)
            return; 
          
          String comName = currentImage.getTitle();
          int dotIndex = comName.lastIndexOf('.');
          if (dotIndex > 0) comName = comName.substring(0, dotIndex);
          String currentName = comName;
          
          if (!validateModels())
            return; 
                   
          runBtn.setText("Processing");
          runBtn.setEnabled(false);
          
          double magRatio = (100.0F / this.Magnification);
          int rwidth = (int)(currentImage.getWidth() * magRatio);
          int rheight = (int)(currentImage.getHeight() * magRatio);
          
          if (Multiple == 1) {
        	Logging.log("\nStarting Multiple Process...");
            int totalZ = currentImage.getNSlices();
            int currentC = currentImage.getC();
            int currentT = currentImage.getT();
            ImageStack currentStack = currentImage.getStack();
            ImageStack resultStack = new ImageStack(rwidth, rheight);
            	new Thread(() -> {
	            	for (int z = 1; z <= totalZ; z++) {
	            		int index = currentImage.getStackIndex(currentC, z, currentT);
	            		String sliceName = currentStack.getSliceLabel(index);
	                    
	            		ImageProcessor ip = currentStack.getProcessor(index); 
	                    if (this.Magnification != 100) {
		                      ip.setInterpolationMethod(1);
		                      ip = ip.resize(rwidth, rheight);
		                    } 
	                    ImageProcessor currentIp = (ip instanceof ij.process.ColorProcessor) ? ip : (ImageProcessor)processorGreyToRGB.convertToRGB(ip, 1);
	                    
	                    ImageProcessor resultIp = null;
	                    try {
		                    if(Mode == 1) {
		                        detector.yoloCrop(currentIp);
		                        Logging.log("\n   Detection Found:" + detector.resultBboxes.size() + " Cells in " + sliceName + ".\n");
		                        resultIp = segmentor.unetDraw(currentIp, detector.getCrops(), detector.getBboxes());
		                        csv.writeSegData(sliceName, segmentor.csvList);
		                    } else if(Mode == 0) {
		                        resultIp = detector.yoloDraw(currentIp);
		                        Logging.log("Detection Found:" + detector.resultBboxes.size() + " Cells in " + sliceName + ".");
		                        csv.writeDetData(sliceName, detector.resultBboxes.size());
		                    }
		                  
	                    } catch(Exception e_s) {
	                        e_s.printStackTrace();
	                    }
	                   
	                    resultStack.addSlice("processed-" + sliceName, resultIp);
	                    
	            	 }
	            	
                    SwingUtilities.invokeLater(() -> {
	                    ImagePlus resultImg = new ImagePlus(currentName + "_processed", resultStack);
	                    resultImg.show();
                        runBtn.setText("Run");
                        runBtn.setEnabled(true);
                    });
                    
            	}).start();
            	
          } else if (Multiple == 0) {
        	  
        	Logging.log("\nStarting Single Process...\n");
        	
            ImageProcessor ip = currentImage.getProcessor();    
            if (this.Magnification != 100) {
                ip.setInterpolationMethod(1);
                ip = ip.resize(rwidth, rheight);
              } 
            ImageProcessor currentIp = (ip instanceof ij.process.ColorProcessor) ? ip : (ImageProcessor)processorGreyToRGB.convertToRGB(ip, 1);
            originalProcessor = currentIp.duplicate();
            
            new Thread(() -> {
                try {
                    ImageProcessor resultIp = null;
                    if(Mode == 1) {
                        detector.yoloCrop(currentIp);
                        Logging.log("   Detection Found:" + detector.resultBboxes.size() + " Cells.\n");
                        DetectionBoxes = segmentor.unetProcess(currentIp, detector.getCrops(), detector.getBboxes());
                        resultIp = updateDisplay(false);
                        Logging.log("\nF-actin Segmentation Finished.");
                        csv.writeSegData(currentName, segmentor.csvList);
                    } else if(Mode == 0) {
                        resultIp = detector.yoloDraw(currentIp);
                        Logging.log("Detection Found:" + detector.resultBboxes.size() + " Cells.");
                        csv.writeDetData(currentName, detector.resultBboxes.size());
                    }
                   
                    ImageProcessor finalResultIp = resultIp;
                    SwingUtilities.invokeLater(() -> {
                        ImagePlus resultImg = new ImagePlus(currentName + "_processed", finalResultIp);
                        resultImg.show();
                        if(Mode == 1) setActiveImage(resultImg);
                        runBtn.setText("Run");
                        runBtn.setEnabled(true);
                    });
                } catch(Exception e_d) {
                    e_d.printStackTrace();
                }
            }).start();
          }
        });
    dialog.add(runBtn);
    
    JButton cancelBtn = new JButton("Cancel");
    cancelBtn.setBounds(330, 565, 80, 20);
    cancelBtn.addActionListener(e -> {
		dialog.dispose();
		try {
		    csv.close();
		} catch (IOException e_f) {
		    throw new RuntimeException(e_f);
		} 
    });
   
    dialog.add(cancelBtn);
    dialog.setLocationRelativeTo((Component)null);
    dialog.setVisible(true);
    Logging.log("det model path: ../imageJ/models/det");
    Logging.log("seg model path: ../imageJ/models/seg");
    Logging.log("<Please select a model>");
  }
  
  
  public String[] getModels(String modelPath, String type) {
    File folder = new File(modelPath);
    File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".onnx"));
    if (files == null || files.length == 0)
      return new String[] { type }; 
    String[] options = new String[files.length + 1];
    options[0] = type;
    for (int i = 1; i < files.length + 1; i++)
      options[i] = files[i - 1].getName(); 
    return options;
  }
  
  
  private boolean validateModels() {
    boolean isValid = true;
    if (this.detector.modelPath == null || this.detector.modelPath.isEmpty()) {
      Logging.logError("Error: Detection model not selected!");
      isValid = false;
    } else if (!(new File(this.detector.modelPath)).exists()) {
      Logging.logError("Error: Detection model file not found: " + this.detector.modelPath);
      isValid = false;
    } 
    if (this.Mode == 1)
      if (this.segmentor.modelPath == null || this.segmentor.modelPath.isEmpty()) {
        Logging.logError("Error: Segmentation model not selected!");
        isValid = false;
      } else if (!(new File(this.segmentor.modelPath)).exists()) {
        Logging.logError("Error: Segmentation model file not found: " + this.segmentor.modelPath);
        isValid = false;
      }  
    return isValid;
  }
  
  
  private ImageProcessor updateDisplay(boolean update) {
      ImageProcessor displayProcessor = originalProcessor.duplicate();
      
      int imgWidth = displayProcessor.getWidth();
      int imgHeight = displayProcessor.getHeight();
      
      int fontSize = Math.max(imgWidth, imgHeight) / 55;
      int lineSize = Math.max(imgWidth, imgHeight) / 300;

	  Font font = new Font("Arial", Font.BOLD, fontSize);
      displayProcessor.setFont(font);
      
      for (DetectionBox box : DetectionBoxes) {
          
    	  drawMaskOverlay(displayProcessor, box);
          
          displayProcessor.setColor(Color.BLUE);
          displayProcessor.setLineWidth(lineSize);
          displayProcessor.drawRect(box.bounds.x, box.bounds.y, box.bounds.width, box.bounds.height);       
      }
      
      for (DetectionBox box : DetectionBoxes) {
    	  
          int[] hist = box.mask.getHistogram();
          int F_actinSize = hist[255];  
          double percent = (double)F_actinSize/box.cellSize * 100;
          String sPercent = String.format("%.1f", percent) + "%";
          
          int strWidth = displayProcessor.getStringWidth(sPercent);
          int strHeight = fontSize;
          displayProcessor.setColor(Color.BLUE);
          displayProcessor.fillRect(box.bounds.x + 2, box.bounds.y + 2, strWidth + 4, strHeight + 2);
    	  
          displayProcessor.setColor(Color.WHITE);
          displayProcessor.drawString(sPercent, box.bounds.x + 4, box.bounds.y + fontSize);
          
      }
      
      if(update) {
          imp.setProcessor(displayProcessor);
          imp.updateAndDraw();
      }
      
      return displayProcessor;
      
  }
 
  
  private void drawMaskOverlay(ImageProcessor processor, DetectionBox box) {
      for (int y = 0; y < box.bounds.height; y++) {
          for (int x = 0; x < box.bounds.width; x++) {
              int maskValue = box.mask.getPixel(x, y);
              if (maskValue > 0) {
                  int imgX = box.bounds.x + x;
                  int imgY = box.bounds.y + y;
                  if (imgX >= 0 && imgX < processor.getWidth() && 
                      imgY >= 0 && imgY < processor.getHeight()) {
                      
                      int originalPixel = processor.getPixel(imgX, imgY);
                      
                      Color maskColor = Color.blue;
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
  
  private DetectionBox findBoxAtPosition(int x, int y) {
      for (DetectionBox box : DetectionBoxes) {
          if (box.bounds.contains(x, y)) {
              return box;
          }
      }
      return null;
  }
  
  private void editMask(DetectionBox box, int x, int y, boolean erase) {
      if (box == null) return;
      
      int maskX = x - box.bounds.x;
      int maskY = y - box.bounds.y;
      
      for (int dy = -BrushSize; dy <= BrushSize; dy++) {
          for (int dx = -BrushSize; dx <= BrushSize; dx++) {
              if (dx * dx + dy * dy <= BrushSize * BrushSize) {
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
  
  private void updateCursor() {
      if (canvas == null) return;
      
      if (isDrawing || isErasing) {
          int cursorSize = Math.max(32, Math.min(64, BrushSize * 4 + 16));
          BufferedImage cursorImage = new BufferedImage(cursorSize, cursorSize, BufferedImage.TYPE_INT_ARGB);
          Graphics2D g2d = cursorImage.createGraphics();
          g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
          
          g2d.setComposite(AlphaComposite.Clear);
          g2d.fillRect(0, 0, cursorSize, cursorSize);
          g2d.setComposite(AlphaComposite.SrcOver);
          
          int centerX = cursorSize / 2;
          int centerY = cursorSize / 2;
          
          g2d.setColor(Color.BLACK);
          g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
          g2d.drawOval(centerX - BrushSize, centerY - BrushSize, BrushSize * 2, BrushSize * 2);
          
          if (isDrawing) {
              g2d.setColor(new Color(255, 0, 0, 80)); 
          } else {
              g2d.setColor(new Color(0, 100, 255, 80)); 
          }
          g2d.fillOval(centerX - BrushSize + 1, centerY - BrushSize + 1, 
                      BrushSize * 2 - 2, BrushSize * 2 - 2);
          
          g2d.setColor(Color.BLACK);
          g2d.setStroke(new BasicStroke(1));
          int crossSize = 4;
          g2d.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY);
          g2d.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize);
          

          g2d.setColor(Color.WHITE);
          g2d.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
          g2d.drawOval(centerX - BrushSize - 1, centerY - BrushSize - 1, 
                      BrushSize * 2 + 2, BrushSize * 2 + 2);
          
          g2d.dispose();
          
 
          Point hotspot = new Point(centerX, centerY);
          String cursorName = isDrawing ? "DrawBrush_" + BrushSize : "EraseBrush_" + BrushSize;
          
          try {
              Cursor customCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                  cursorImage, hotspot, cursorName);
              canvas.setCursor(customCursor);
          } catch (Exception ex) {
              canvas.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
          }
          
      } else {
          canvas.setCursor(Cursor.getDefaultCursor());
      }
  }
  
  private String saveResults() {
	  
	  if(DetectionBoxes == null) {
		  String failResult = "None";
		  return failResult;
	  }

	  String name = imp.getTitle();
      File outputDir = new File(baseModelFolder, "results");
      if (!outputDir.exists()) outputDir.mkdirs();

      String timestamp = new SimpleDateFormat("yyyyMMddHH").format(new Date());
      File timeDir = new File(outputDir, timestamp);
      if (!timeDir.exists()) timeDir.mkdirs();

      File masksDir = new File(timeDir, "masks");
      File cropsDir = new File(timeDir, "crops");
      File labelsDir = new File(timeDir, "labels");
      File imagesDir = new File(timeDir, "images");
      masksDir.mkdirs();
      cropsDir.mkdirs();
      labelsDir.mkdirs();
      imagesDir.mkdirs();


      File labelFile = new File(labelsDir, name + ".txt");
      try (BufferedWriter writer = new BufferedWriter(new FileWriter(labelFile))) {
          int imgW = originalProcessor.getWidth();
          int imgH = originalProcessor.getHeight();
          int idx = 0;
          for (DetectionBox box : DetectionBoxes) {
              Rectangle r = box.bounds;
              
              double xCenter = (r.x + r.width / 2.0) / imgW;
              double yCenter = (r.y + r.height / 2.0) / imgH;
              double w = r.width / (double) imgW;
              double h = r.height / (double) imgH;

              writer.write(String.format("%d %.6f %.6f %.6f %.6f", 0, xCenter, yCenter, w, h));
              writer.newLine();
              idx++;
          }
      } catch (IOException e) {
          e.printStackTrace();
      }


      int maskIndex = 0;
      for (DetectionBox box : DetectionBoxes) {
          if (box.mask != null) {
              ImagePlus maskImp = new ImagePlus("mask", box.mask);
              FileSaver fs = new FileSaver(maskImp);
              fs.saveAsJpeg(new File(masksDir, name + "_mask_" + maskIndex + ".jpg").getAbsolutePath());
          }
          maskIndex++;
      }


      ImagePlus imgImp = new ImagePlus("original", originalProcessor.duplicate());
      new FileSaver(imgImp).saveAsJpeg(new File(imagesDir, name + ".jpg").getAbsolutePath());


      int cropIndex = 0;
      for (DetectionBox box : DetectionBoxes) {
          Rectangle r = box.bounds;
          if (r.width > 0 && r.height > 0) {
              ImageProcessor dup = originalProcessor.duplicate();
              dup.setRoi(r);
              ImageProcessor cropped = dup.crop();
              ImagePlus cropImp = new ImagePlus("crop", cropped);
              FileSaver fs = new FileSaver(cropImp);
              fs.saveAsJpeg(new File(cropsDir, name + "_crop_" + cropIndex + ".jpg").getAbsolutePath());
          }
          cropIndex++;
      }
      
      String succussResult = timeDir.getAbsolutePath();
      return succussResult;
  }
  
  
  @Override
  public void mousePressed(MouseEvent e) {
      e.consume();
      
      int x = canvas.offScreenX(e.getX());
      int y = canvas.offScreenY(e.getY());
      
      activeBox = findBoxAtPosition(x, y);
      
      if(isDeleting == true && activeBox != null) {
		  DetectionBoxes.remove(activeBox);
		  updateDisplay(true);
		  return;
      }
      
      if (isAdding == true) {
          addStartX = x;
          addStartY = y;
    	  addBox = new DetectionBox(new Rectangle(addStartX, addStartY, 0, 0), new ByteProcessor(1, 1), 1); 
    	  DetectionBoxes.add(addBox);
          return;
      }
      
      if(isDrawing == true || isErasing == true) {
          if (activeBox != null) {
              lastMouseX = -1;
              lastMouseY = -1;
              
              editMaskWithInterpolation(activeBox, x, y, isErasing);
              updateDisplay(true);
          }
          return;
      }
      e.consume();
  }
  
  @Override
  public void mouseDragged(MouseEvent e) {
      e.consume();

      int x = canvas.offScreenX(e.getX());
      int y = canvas.offScreenY(e.getY());
      
      if (isAdding == true) {
    	  if (addStartX >= 0 && addStartY >= 0) {
              int rx = Math.min(addStartX, x);
              int ry = Math.min(addStartY, y);
              int rw = Math.abs(x - addStartX);
              int rh = Math.abs(y - addStartY);
              addBox.bounds = new Rectangle(rx, ry, rw, rh);
              addBox.mask = new ByteProcessor(rw, rh);
              addBox.cellSize = (int)(rw * rh * 3.14 / 4);
              updateDisplay(true);
          }
          return;
      }
      
      if(isDrawing == true || isErasing == true) {
    	  
          if (activeBox.bounds.contains(x, y)) {
        	  
        	  editMaskWithInterpolation(activeBox, x, y, isErasing);
              updateDisplay(true);
          }
          
          return;
      }
      
      e.consume();
  }
  
  @Override
  public void mouseReleased(MouseEvent e) {
      e.consume();
      int x = canvas.offScreenX(e.getX());
      int y = canvas.offScreenY(e.getY());
      
      if (isAdding == true) {
    	  addBox = null;
    	  addStartX = -1;
    	  addStartY = -1;
      }
      
	  if(isDrawing == true || isErasing == true) {
	      activeBox = null;
	      lastMouseX = -1;
	      lastMouseY = -1;
	  }
	       
      e.consume();
  }
  
  @Override
  public void mouseMoved(MouseEvent e) {
      lastMouseX = -1;
      lastMouseY = -1;
      
      e.consume();
  }
  
  @Override
  public void keyPressed(KeyEvent e) {
      if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
          isDrawing = false;
          isErasing = false;
          updateCursor();
      }
      
      e.consume();
  }
  
  @Override public void mouseClicked(MouseEvent e) { e.consume(); }
  @Override public void mouseEntered(MouseEvent e) { e.consume(); }
  @Override public void mouseExited(MouseEvent e) { e.consume(); }
  @Override public void keyReleased(KeyEvent e) { e.consume(); }
  @Override public void keyTyped(KeyEvent e) { e.consume(); }
  
  
  public static void main(String[] args) throws Exception {
	new ImageJ();
	
    Class<?> clazz = F_actin.class;
    URL url = clazz.getProtectionDomain().getCodeSource().getLocation();
    File file = new File(url.toURI());
    System.setProperty("plugins.dir", file.getAbsolutePath());
    ImagePlus image = IJ.openImage("images/S8.jpg");
    image.show();
    IJ.runPlugIn(clazz.getName(), "");
  }
  
}
