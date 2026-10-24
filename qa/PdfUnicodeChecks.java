package app.quietread;
public final class PdfUnicodeChecks {
 public static void main(String[] args){
  String source="1 beginbfrange\n<0020> <0022> <1f600>\nendbfrange\n1 beginbfchar\n<0030> <1d11e>\nendbfchar\n";
  String fixed=PdfUnicodeCMap.repair(source);
  if(!fixed.contains("<0020> <0022> [<D83DDE00><D83DDE01><D83DDE02>]"))throw new AssertionError(fixed);
  if(!fixed.contains("<0030> <D834DD1E>"))throw new AssertionError(fixed);
  String boundary=PdfUnicodeCMap.repair("1 beginbfrange\n<0001> <0003> <1f7ff>\nendbfrange");
  if(!boundary.contains("[<D83DDFFF><D83EDC00><D83EDC01>]"))throw new AssertionError(boundary);
  String bmp="1 beginbfchar\n<0001> <041F>\nendbfchar\n";
  if(!PdfUnicodeCMap.repair(bmp).equals(bmp+"\n"))throw new AssertionError("BMP modified");
  if(!PdfUnicodeCMap.repair(fixed).trim().equals(fixed.trim()))throw new AssertionError("not idempotent");
  System.out.println("PASS: BMP, supplementary characters, ranges crossing surrogate boundaries, idempotence");
 }
}
