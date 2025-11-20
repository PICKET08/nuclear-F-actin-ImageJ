package cn.ck.utils;

import java.awt.Color;
import java.awt.Font;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

public class Logging {
  private static JTextPane logPane;
  
  private static StyledDocument doc;
  
  private static Style normalStyle;
  
  private static Style errorStyle;
  
  public static void setLogArea(JTextPane pane) {
    logPane = pane;
    doc = pane.getStyledDocument();
    Font logFont = new Font("Consolas", 0, 12);
    normalStyle = pane.addStyle("normal", null);
    StyleConstants.setForeground(normalStyle, Color.BLACK);
    StyleConstants.setFontFamily(normalStyle, logFont.getFamily());
    StyleConstants.setFontSize(normalStyle, logFont.getSize());
    errorStyle = pane.addStyle("error", null);
    StyleConstants.setForeground(errorStyle, Color.RED);
    StyleConstants.setFontFamily(errorStyle, logFont.getFamily());
    StyleConstants.setFontSize(errorStyle, logFont.getSize());
    StyleConstants.setBold(errorStyle, true);
  }
  
  public static void log(String message) {
    appendText(String.valueOf(message) + "\n", normalStyle);
  }
  
  public static void logError(String message) {
    appendText(String.valueOf(message) + "\n", errorStyle);
  }
  
  public static void logProgress(String message) {
    if (logPane != null)
      SwingUtilities.invokeLater(() -> {
            try {
              int length = doc.getLength();
              if (length > 0) {
                String text = doc.getText(0, length);
                int lastNewline = text.lastIndexOf('\n', length - 2);
                int startPos = (lastNewline == -1) ? 0 : (lastNewline + 1);
                doc.remove(startPos, length - startPos);
                doc.insertString(startPos, message, normalStyle);
              } else {
                doc.insertString(0, message, normalStyle);
              } 
              logPane.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
              e.printStackTrace();
            } 
          }); 
  }
  
  private static void appendText(String text, Style style) {
    if (logPane != null)
      SwingUtilities.invokeLater(() -> {
            try {
              doc.insertString(doc.getLength(), text, style);
              logPane.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
              e.printStackTrace();
            } 
          }); 
  }
}
