package juloo.keyboard2;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class TestSilmirTranslator
{
  public static void main(String[] a) throws Exception
  {
    if (a.length != 6)
      throw new IllegalArgumentException("expected 5 assets + test TSV");

    SilmirTranslator t = new SilmirTranslator(
      new FileInputStream(a[0]), new FileInputStream(a[1]),
      new FileInputStream(a[2]), new FileInputStream(a[3]), new FileInputStream(a[4]));

    BufferedReader br = new BufferedReader(new InputStreamReader(
      new FileInputStream(a[5]), StandardCharsets.UTF_8));
    String line;
    int total = 0, failed = 0;
    while ((line = br.readLine()) != null)
    {
      String[] c = line.split("\t", 3);
      if (c.length != 3)
        continue;
      total++;
      String got;
      if (c[0].equals("ru"))
        got = t.russianToSilmir(c[1]);
      else if (c[0].equals("sil"))
        got = t.silmirToRussian(c[1]);
      else
        got = t.translate(c[1]);
      if (!got.equals(c[2]))
      {
        failed++;
        System.err.println("FAIL: " + c[1] + " | expected=" + c[2] + " | got=" + got);
      }
    }
    br.close();
    System.out.println("Sil'mir v7 tests: " + (total - failed) + "/" + total);
    if (failed != 0)
      System.exit(2);
  }
}
