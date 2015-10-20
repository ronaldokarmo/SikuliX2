/*
 * Copyright 2010-2014, Sikuli.org, sikulix.com
 * Released under the MIT License.
 *
 * modified RaiMan
 */
package org.sikuli.script;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Range;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.sikuli.util.Debug;
import org.sikuli.util.Settings;

public class Finder {

  static RunTime runTime = RunTime.get();

  //<editor-fold defaultstate="collapsed" desc="logging">
  private static final int lvl = 3;
  private static final Logger logger = LogManager.getLogger("SX.Finder");

  private static void log(int level, String message, Object... args) {
    if (Debug.is(lvl)) {
      message = String.format(message, args).replaceFirst("\\n", "\n          ");
      if (level == lvl) {
        logger.debug(message, args);
      } else if (level > lvl) {
        logger.trace(message, args);
      } else if (level == -1) {
        logger.error(message, args);
      } else {
        logger.info(message, args);
      }
    }
  }

  private void logp(String message, Object... args) {
    System.out.println(String.format(message, args));
  }

  public void terminate(int retval, String message, Object... args) {
    logger.fatal(String.format(" *** terminating: " + message, args));
    System.exit(retval);
  }
//</editor-fold>

  private static final double downSimDiff = 0.15;

  private boolean isImage = false;
  private Image image = null;

  private boolean isRegion = false;
  private Region region = null;
  private int offX = 0, offY = 0;

  private boolean isMat = false;

  private Mat base = new Mat();

  private static class Probe {

    public Pattern pattern = null;
    public double similarity = 0;
    public double downSim = 0;
    public Image img = null;
    public Mat mat = null;
    public Region lastSeen = null;
    public double lastSeenScore = 0;

    private boolean valid = false;

    public Probe(Pattern pattern) {
      if (pattern.isValid()) {
        this.pattern = pattern;
        similarity = pattern.getSimilar();
        downSim = ((int) ((similarity - downSimDiff) * 100)) / 100.0;
        img = pattern.getImage();
        mat = img.getMat();
        if (null != img.getLastSeen()) {
          lastSeen = new Region(img.getLastSeen());
          lastSeenScore = img.getLastSeenScore();
        }
      }
    }

    public boolean isValid() {
      return valid;
    }
  }

  public static class Found implements Iterator<Match> {

    public String name = "";
    public boolean success = false;
    public Region.FindType type = Region.FindType.ONE;

    public Region region = null;
    private int baseX = 0;
    private int baseY = 0;
    public Image image = null;
    public boolean inImage = false;

    public Mat base = null;

    public Pattern pattern = null;
    public long timeout = 0;
    public long elapsed = -1;
    public Match match = null;

    public Finder finder = null;

    public boolean isIterator = false;
    public Mat result;
    private double currentScore = -1;
    private int currentX = -1;
    private int currentY = -1;
    private int width = 0;
    private int height = 0;
    private Core.MinMaxLocResult mRes = null;

    @Override
    public synchronized boolean hasNext() {
      return hasNext(true);
    }
    
    public synchronized boolean hasNext(boolean withTrace) {
      boolean success = false;
      if (currentScore < 0) {
        if (currentScore < -0.5) {
          width = pattern.getImage().getWidth();
          height = pattern.getImage().getHeight();
        } else if (currentScore < 0) {
          int newX = Math.max(currentX - width, 0);
          int newY = Math.max(currentY - height, 0);
          int newXX = Math.min(newX + 2 * width, result.cols());
          int newYY = Math.min(newY + 2 * height, result.rows());
          result.colRange(newX, newXX).rowRange(newY, newYY).setTo(new Scalar(0f));
        }
        mRes = Core.minMaxLoc(result);
        currentScore = mRes.maxVal;
        currentX = baseX + (int) mRes.maxLoc.x;
        currentY = baseY + (int) mRes.maxLoc.y;
      }
      if (currentScore > pattern.getSimilar()) {
        success = true;
      }
      if (withTrace) {
        log(lvl + 1, "hasNext: %.4f (%d, %d)", currentScore, currentX, currentY);
      }
      return success;
    }

    @Override
    public synchronized Match next() {
      Match match = null;
      if (hasNext(false)) {
        match = new Match(new Rectangle(currentX, currentY, width, height), currentScore);
        currentScore = -0.5;
      }
      log(lvl + 1, "next: %s", match == null ? "no match" : match.toJSON());
      return match;
    }

    public Found(Finder fndr) {
      inImage = fndr.isImage;
      region = fndr.region;
      image = fndr.image;
      if (!inImage) {
        baseX = region.x;
        baseY = region.y;
      }
      finder = fndr;
    }

    public String toJSON() {
      String template = "{name:[\"%s\", \"%s\"], elapsed:%s, pattern:%s, %s:%s, match:%s}";
      String inWhat = inImage ? "in_image" : "in_region";
      String inWhatJSON = inImage ? image.toJSON(false) : region.toJSON();
      String[] nameParts = name.split("_");
      String found = String.format(template, nameParts[0], nameParts[1], elapsed,
          pattern.toJSON(false), inWhat, inWhatJSON, match.toJSON());
      return found;
    }

    @Override
    public String toString() {
      return toJSON();
    }
  }

  private Finder() {
  }

  public Finder(Image img) {
    if (img != null && img.isValid()) {
      base = img.getMat();
      isImage = true;
    } else {
      log(-1, "init: invalid image: %s", img);
    }
  }

  public Finder(Region reg) {
    if (reg != null) {
      region = reg;
      offX = region.x;
      offY = region.y;
      isRegion = true;
    } else {
      log(-1, "init: invalid region: %s", reg);
    }
  }

  protected Finder(Mat base) {
    if (base != null) {
      this.base = base;
      isMat = true;
    } else {
      log(-1, "init: invalid CV-Mat: %s", base);
    }
  }

  public void setIsMultiFinder() {
    terminate(1, "TODO setIsMultiFinder()");
  }

  public boolean setImage(Image img) {
    return isImage;
  }

  public boolean inImage() {
    return isImage;
  }

  public boolean setRegion(Region reg) {
    return isRegion;
  }

  public boolean inRegion() {
    return isImage;
  }

  protected void setBase(BufferedImage bImg) {
    terminate(1, "TODO setBase(BufferedImage bImg)");
//    base = new Image(bImg, "").getMat();
  }

  protected long setBase() {
    if (!isRegion) {
      return 0;
    }
    long begin_t = new Date().getTime();
    base = region.captureThis().getMat();
    return new Date().getTime() - begin_t;
  }

  public boolean isValid() {
    if (!isImage && !isRegion) {
      return false;
    }
    return true;
  }

  public boolean find(Object target, Found found) {
    try {
      doFind(evalTarget(target), found);
    } catch (IOException ex) {
      log(-1, "find: Exception: %s", ex.getMessage());
    }
    return found.success;
  }

  public boolean findAll(Object target, Found found) {
    try {
      found.type = Region.FindType.ALL;
      doFind(evalTarget(target), found);
    } catch (IOException ex) {
      log(-1, "findAll: Exception: %s", ex.getMessage());
    }
    return found.success;
  }

  public String findText(String text) {
    terminate(1, "findText: not yet implemented");
    return null;
  }

  private final double resizeMinFactor = 1.5;
  private final double[] resizeLevels = new double[]{1f, 0.4f};
  private int resizeMaxLevel = resizeLevels.length - 1;
  private double resizeMinSim = 0.8;
  private boolean useOriginal = false;

  public void setUseOriginal() {
    useOriginal = true;
  }

  private void doFind(Pattern pattern, Found found) {
    boolean success = false;
    long begin_t = 0;
    Mat result = new Mat();
    Core.MinMaxLocResult mMinMax = null;
    Match mFound = null;
    Probe probe = new Probe(pattern);
    found.base = base;
    boolean isIterator = Region.FindType.ALL.equals(found.type);
    if (!isIterator && !useOriginal && Settings.CheckLastSeen && probe.lastSeen != null) {
      // ****************************** check last seen
      begin_t = new Date().getTime();
      Finder lastSeenFinder = new Finder(probe.lastSeen);
      lastSeenFinder.setUseOriginal();
      lastSeenFinder.find(new Pattern(probe.img).similar(probe.lastSeenScore - 0.01), found);
      if (found.match != null) {
        mFound = found.match;
        success = true;
        log(lvl + 1, "doFind: checkLastSeen: success %d msec", new Date().getTime() - begin_t);
      } else {
        log(lvl + 1, "doFind: checkLastSeen: not found %d msec", new Date().getTime() - begin_t);
      }
    }
    if (!success) {
      if (isRegion) {
        log(lvl + 1, "doFind: capture: %d msec", setBase());
      }
      double rfactor = 0;
      if (!isIterator && !useOriginal && probe.img.getResizeFactor() > resizeMinFactor) {
        // ************************************************* search in downsized
        begin_t = new Date().getTime();
        double imgFactor = probe.img.getResizeFactor();
        Size sb, sp;
        Mat mBase = new Mat(), mPattern = new Mat();
        result = null;
        for (double factor : resizeLevels) {
          rfactor = factor * imgFactor;
          sb = new Size(base.cols() / rfactor, base.rows() / rfactor);
          sp = new Size(probe.mat.cols() / rfactor, probe.mat.rows() / rfactor);
          Imgproc.resize(base, mBase, sb, 0, 0, Imgproc.INTER_AREA);
          Imgproc.resize(probe.mat, mPattern, sp, 0, 0, Imgproc.INTER_AREA);
          result = doFindMatch(probe, mBase, mPattern);
          mMinMax = Core.minMaxLoc(result);
          if (mMinMax.maxVal > probe.downSim) {
            break;
          }
        }
        log(lvl + 1, "doFindDown: %d msec", new Date().getTime() - begin_t);
      }
      if (!isIterator && mMinMax != null) {
        // ************************************* check after downsized success
        int maxLocX = (int) (mMinMax.maxLoc.x * rfactor);
        int maxLocY = (int) (mMinMax.maxLoc.y * rfactor);
        begin_t = new Date().getTime();
        int margin = ((int) probe.img.getResizeFactor()) + 1;
        Rect r = new Rect(Math.max(0, maxLocX - margin), Math.max(0, maxLocY - margin),
            Math.min(probe.img.getWidth() + 2 * margin, base.width()),
            Math.min(probe.img.getHeight() + 2 * margin, base.height()));
        result = doFindMatch(probe, base.submat(r), probe.mat);
        mMinMax = Core.minMaxLoc(result);
        if (mMinMax.maxVal > probe.similarity) {
          mFound = new Match((int) mMinMax.maxLoc.x + offX + r.x, (int) mMinMax.maxLoc.y + offY + r.y,
              probe.img.getWidth(), probe.img.getHeight(), mMinMax.maxVal);
          success = true;
        }
        log(lvl + 1, "doFind: check after doFindDown %%%.2f(%%%.2f) %d msec",
            mMinMax.maxVal * 100, probe.similarity * 100, new Date().getTime() - begin_t);
      }
      if (isIterator || (!success && useOriginal)) {
        // ************************************** search in original 
        begin_t = new Date().getTime();
        result = doFindMatch(probe, base, probe.mat);
        mMinMax = Core.minMaxLoc(result);
        if (mMinMax != null && mMinMax.maxVal > probe.similarity) {
          mFound = new Match((int) mMinMax.maxLoc.x + offX, (int) mMinMax.maxLoc.y + offY,
              probe.img.getWidth(), probe.img.getHeight(), mMinMax.maxVal);
          success = true;
        }
        if (!useOriginal) {
          log(lvl + 1, "doFind: search in original: %d msec", new Date().getTime() - begin_t);
        }
      }
    }
    if (success) {
      probe.img.setLastSeen(mFound.getRect(), mFound.getScore());
      found.match = mFound;
      if (Region.FindType.ALL.equals(found.type)) {
        found.result = result;
      }
    }
    found.success = success;
  }

  private Mat doFindMatch(Probe probe, Mat base, Mat target) {
    Mat res = new Mat();
    Mat bi = new Mat();
    Mat pi = new Mat();
    if (!probe.img.isPlainColor()) {
      Imgproc.matchTemplate(base, target, res, Imgproc.TM_CCOEFF_NORMED);
    } else {
      if (probe.img.isBlack()) {
        Core.bitwise_not(base, bi);
        Core.bitwise_not(target, pi);
      } else {
        bi = base;
        pi = target;
      }
      Imgproc.matchTemplate(bi, pi, res, Imgproc.TM_SQDIFF_NORMED);
      Core.subtract(Mat.ones(res.size(), CvType.CV_32F), res, res);
    }
    return res;
  }

  private static Rect getSubMatRect(Mat mat, int x, int y, int w, int h, int margin) {
    x = Math.max(0, x - margin);
    y = Math.max(0, y - margin);
    w = Math.min(w + 2 * margin, mat.width() - x);
    h = Math.min(h + 2 * margin, mat.height() - y);
    return new Rect(x, y, w, h);
  }

  public boolean hasChanges(Mat current) {
    int PIXEL_DIFF_THRESHOLD = 5;
    int IMAGE_DIFF_THRESHOLD = 5;
    Mat bg = new Mat();
    Mat cg = new Mat();
    Mat diff = new Mat();
    Mat tdiff = new Mat();

    Imgproc.cvtColor(base, bg, Imgproc.COLOR_BGR2GRAY);
    Imgproc.cvtColor(current, cg, Imgproc.COLOR_BGR2GRAY);
    Core.absdiff(bg, cg, diff);
    Imgproc.threshold(diff, tdiff, PIXEL_DIFF_THRESHOLD, 0.0, Imgproc.THRESH_TOZERO);
    if (Core.countNonZero(tdiff) <= IMAGE_DIFF_THRESHOLD) {
      return false;
    }

    Imgproc.threshold(diff, diff, PIXEL_DIFF_THRESHOLD, 255, Imgproc.THRESH_BINARY);
    Imgproc.dilate(diff, diff, new Mat());
    Mat se = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
    Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_CLOSE, se);

    List<MatOfPoint> points = new ArrayList<MatOfPoint>();
    Mat contours = new Mat();
    Imgproc.findContours(diff, points, contours, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
    int n = 0;
    for (Mat pm : points) {
      log(lvl, "(%d) %s", n++, pm);
      printMatI(pm);
    }
    log(lvl, "contours: %s", contours);
    printMatI(contours);
    return true;
  }

  public void setMinChanges(int min) {
    terminate(1, "setMinChanges");
  }

  protected Pattern evalTarget(Object target) throws IOException {
    boolean findingText = false;
    Image img = null;
    Pattern pattern = null;
    if (target instanceof String) {
      if (((String) target).startsWith("\t") && ((String) target).endsWith("\t")) {
        findingText = true;
      } else {
        img = new Image((String) target);
        if (img.isValid()) {
          pattern = new Pattern(img);
        } else if (img.isText()) {
          findingText = true;
        } else {
          throw new IOException("Region: doFind: Image not useable: " + target.toString());
        }
      }
      if (findingText) {
        if (TextRecognizer.getInstance() != null) {
          pattern = new Pattern((String) target, Pattern.Type.TEXT);
        }
      }
    } else if (target instanceof Pattern) {
      if (((Pattern) target).isValid()) {
        pattern = (Pattern) target;
      } else {
        throw new IOException("Region: doFind: Pattern not useable: " + target.toString());
      }
    } else if (target instanceof Image) {
      if (((Image) target).isValid()) {
        pattern = new Pattern((Image) target);
      } else {
        throw new IOException("Region: doFind: Image not useable: " + target.toString());
      }
    }
    if (null == pattern) {
      throw new UnsupportedOperationException("Region: doFind: invalid target: " + target.toString());
    }
    return pattern;
  }

  private static void printMatI(Mat mat) {
    int[] data = new int[mat.channels()];
    for (int r = 0; r < mat.rows(); r++) {
      for (int c = 0; c < mat.cols(); c++) {
        mat.get(r, c, data);
        log(lvl, "(%d, %d) %s", r, c, Arrays.toString(data));
      }
    }
  }

}
