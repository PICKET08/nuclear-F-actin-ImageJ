package cn.ck.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.List;

public class CsvLogger {
  private final File csvFile;
  
  private final BufferedWriter writer;
  
  public CsvLogger(String projectDir, List<String> headers) throws IOException {
    File outputDir = new File(projectDir, "output");
    if (!outputDir.exists() && 
      !outputDir.mkdirs())
      throw new IOException("Output Dir creation failed for unknown reasons"); 
    String timestamp = (new SimpleDateFormat("yyMMdd-HHmm")).format(new Date());
    this.csvFile = new File(outputDir, String.valueOf(timestamp) + ".csv");
    this.writer = new BufferedWriter(new FileWriter(this.csvFile, false));
    writeRow(headers);
  }
  
  public void writeSegData(String name, List<? extends Number> values) throws IOException {
	    DecimalFormat df = new DecimalFormat("#.#####"); 
	    StringBuilder line = new StringBuilder();
	    line.append(name);
	    for (Number val : values) {
	        line.append(",").append(df.format(val)); 
	    }
	    line.append("\n");
	    this.writer.write(line.toString());
	    this.writer.flush();
	}
  
  public void writeDetData(String name, int values) throws IOException {
    StringBuilder line = new StringBuilder();
    line.append(name);
    line.append(",").append(values);
    line.append("\n");
    this.writer.write(line.toString());
    this.writer.flush();
  }
  
  private void writeRow(List<String> columns) throws IOException {
    String line = String.valueOf(String.join(",", (Iterable)columns)) + "\n";
    this.writer.write(line);
    this.writer.flush();
  }
  
  public void close() throws IOException {
    this.writer.close();
  }
  
  public String getCsvFilePath() {
    return this.csvFile.getAbsolutePath();
  }
}

