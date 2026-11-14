package vn.ptit.slms.file;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import java.awt.Color;
import java.io.*;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;

/** Prints the stored minutes snapshot rather than the current operational records. */
public final class MinutesPdf {
 private static final Color GREEN=new Color(23,107,81), MUTED=new Color(100,116,110), BORDER=new Color(218,227,221);
 private final Font body,bold,title,small;
 private MinutesPdf() throws Exception {
  body=font("NotoSans-Regular.ttf",9,Color.BLACK);bold=font("NotoSans-Bold.ttf",9,Color.BLACK);
  title=font("NotoSans-Bold.ttf",18,GREEN);small=font("NotoSans-Regular.ttf",8,MUTED);
 }
 private static Font font(String name,float size,Color color)throws Exception {
  try(var input=MinutesPdf.class.getResourceAsStream("/fonts/"+name)){
   if(input==null)throw new IOException("Missing PDF font: "+name);
   return new Font(BaseFont.createFont(name,BaseFont.IDENTITY_H,BaseFont.EMBEDDED,false,input.readAllBytes(),null),size,Font.NORMAL,color);
  }
 }
 public static byte[] render(String type,long id,Map<String,Object> minutes,Map<String,Object> warehouse){
  try{return new MinutesPdf().create(type,id,minutes,warehouse);}catch(Exception e){throw new IllegalStateException("Không thể tạo biên bản PDF.",e);}
 }
 @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();}
 @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value){return value instanceof List<?>?(List<Map<String,Object>>)value:List.of();}
 private static String text(Object value){return value==null||value.toString().isBlank()?"—":value.toString();}
 private static String number(Object value){if(value==null)return "—";var format=new DecimalFormat("#,##0.###",DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN")));return format.format(new BigDecimal(value.toString()));}
 private static String date(Object value){
  if(value==null)return "—";
  var format=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
  if(value instanceof java.sql.Timestamp stamp)return stamp.toInstant().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(format);
  try{return OffsetDateTime.parse(value.toString()).atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).format(format);}catch(Exception ignored){return text(value);}
 }
 private static String status(Object value){return switch(text(value)){case "MATCHED"->"Khớp";case "ADJUSTED"->"Đã điều chỉnh";case "CLOSED"->"Đã đóng";case "COUNTED"->"Đã đếm";case "VARIANCE"->"Chênh lệch";default->text(value);};}
 private PdfPCell cell(String value,Font font,Color background,int alignment){var cell=new PdfPCell(new Phrase(value,font));cell.setPadding(8);cell.setBorderColor(BORDER);cell.setVerticalAlignment(Element.ALIGN_MIDDLE);cell.setHorizontalAlignment(alignment);if(background!=null)cell.setBackgroundColor(background);return cell;}
 private void info(Document doc,String label,Object value)throws DocumentException {var p=new Paragraph();p.setLeading(15);p.setSpacingAfter(5);p.add(new Chunk(label+": ",bold));p.add(new Chunk(text(value),body));doc.add(p);}
 private void heading(Document doc,String value)throws DocumentException{var p=new Paragraph(value,bold);p.setSpacingBefore(18);p.setSpacingAfter(10);doc.add(p);}
 private PdfPTable table(float[] widths,String... headers)throws DocumentException {var table=new PdfPTable(widths);table.setWidthPercentage(100);table.setHeaderRows(1);table.setSplitLate(false);for(String header:headers)table.addCell(cell(header,bold,new Color(237,244,239),Element.ALIGN_LEFT));return table;}
 private byte[] create(String type,long id,Map<String,Object> minutes,Map<String,Object> warehouse)throws Exception {
  boolean count=type.equals("count");var snapshot=map(minutes.get("snapshot"));var source=count?snapshot:map(snapshot.get("shipment"));
  try(var output=new ByteArrayOutputStream()){
   var doc=new Document(PageSize.A4,36,36,38,48);var writer=PdfWriter.getInstance(doc,output);
   writer.setPageEvent(new PdfPageEventHelper(){@Override public void onEndPage(PdfWriter writer,Document document){
    var canvas=writer.getDirectContent();canvas.setColorStroke(BORDER);canvas.moveTo(36,35);canvas.lineTo(559,35);canvas.stroke();
    ColumnText.showTextAligned(canvas,Element.ALIGN_LEFT,new Phrase("SLMS M01  |  "+(count?"Kiểm kê":"Bàn giao")+" #"+id,small),36,23,0);
    ColumnText.showTextAligned(canvas,Element.ALIGN_RIGHT,new Phrase("Trang "+writer.getPageNumber(),small),559,23,0);
   }});
   doc.addTitle(count?"Biên bản kiểm kê kho":"Biên bản bàn giao hàng hóa");doc.addAuthor("SLMS M01");doc.open();
   var brand=new Paragraph("SLMS / QUẢN LÝ KHO VÀ TỒN KHO",small);brand.setSpacingAfter(16);doc.add(brand);
   var name=new Paragraph(count?"BIÊN BẢN KIỂM KÊ KHO":"BIÊN BẢN BÀN GIAO HÀNG HÓA",title);name.setAlignment(Element.ALIGN_CENTER);name.setSpacingAfter(8);doc.add(name);
   var ref=new Paragraph("Số biên bản: "+(count?"KK-":"BG-")+id+"  |  Mã "+(count?"đợt":"phiếu")+": "+text(source.get("code")),small);ref.setAlignment(Element.ALIGN_CENTER);ref.setSpacingAfter(22);doc.add(ref);
   info(doc,"Kho",text(warehouse.get("code"))+" - "+text(warehouse.get("name")));
   if(count){
    info(doc,"Thời gian mở đợt",date(source.get("opened_at")));info(doc,"Thời gian khóa biên bản",date(minutes.get("locked_at")));
    info(doc,"Người phụ trách",source.get("owner_id"));info(doc,"Người khóa biên bản",minutes.get("locked_by"));
    heading(doc,"1. Kết quả kiểm kê");
    var table=table(new float[]{.55f,1.65f,1.3f,1.15f,1.05f,1.1f,1.45f},"STT","Mã hàng","Vị trí","Ghi sổ khi đếm","Thực đếm","Chênh lệch","Kết quả");
    var items=rows(source.get("items"));int index=0;
    for(var row:items){Color background=index%2==0?Color.WHITE:new Color(248,250,248);String[] values={String.valueOf(++index),text(row.get("sku_code")),text(row.get("location_code")),number(row.get("book_at_count")),number(row.get("counted_qty")),number(row.get("delta")),status(row.get("status"))};for(int c=0;c<values.length;c++)table.addCell(cell(values[c],body,background,c>=3&&c<=5?Element.ALIGN_RIGHT:Element.ALIGN_LEFT));}
    if(items.isEmpty()){var empty=cell("Không có dòng kiểm kê.",body,null,Element.ALIGN_CENTER);empty.setColspan(7);table.addCell(empty);}doc.add(table);
    var note=new Paragraph("Số lượng theo đơn vị cơ sở của từng hàng hóa. Chênh lệch = thực đếm - ghi sổ khi đếm.",small);note.setSpacingBefore(8);doc.add(note);
    
    signatures(doc,"2. Ghi chú và kết luận",text(minutes.get("note")),new String[]{"Người kiểm kê","Người phụ trách","Người xác nhận"},new String[]{String.join(", ",items.stream().map(r->text(r.get("assigned_to"))).filter(s->!s.equals("—")).distinct().toList()),text(source.get("owner_id")),text(minutes.get("locked_by"))});
   }else{
    info(doc,"Thời gian bàn giao",date(minutes.get("handed_at")));info(doc,"Người giao",minutes.get("handed_by"));info(doc,"Người nhận",minutes.get("receiver_name"));info(doc,"Chứng từ nguồn",source.get("source_ref"));info(doc,"Tham chiếu bàn giao",minutes.get("source_ref"));
    heading(doc,"1. Hàng hóa bàn giao");var table=table(new float[]{.6f,2.7f,1.4f,1.3f},"Dòng","Mã hàng","Lượng yêu cầu","Đã bàn giao");
    for(var row:rows(source.get("lines"))){BigDecimal picked=rows(source.get("reservations")).stream().filter(r->Objects.equals(text(r.get("line_no")),text(row.get("line_no")))) .map(r->new BigDecimal(Objects.toString(r.get("qty_picked"),"0"))).reduce(BigDecimal.ZERO,BigDecimal::add);for(String value:new String[]{text(row.get("line_no")),text(row.get("sku_code")),number(row.get("requested_qty")),number(picked)})table.addCell(cell(value,body,null,Element.ALIGN_LEFT));}doc.add(table);
    var note=new Paragraph("Số lượng theo đơn vị cơ sở của từng hàng hóa.",small);note.setSpacingBefore(8);doc.add(note);
    
    signatures(doc,"2. Xác nhận bàn giao","Các bên kiểm tra, đối chiếu và xác nhận hàng hóa bàn giao theo nội dung trên.",new String[]{"Người giao","Người nhận"},new String[]{text(minutes.get("handed_by")),text(minutes.get("receiver_name"))});
   }
   doc.close();return output.toByteArray();
  }
 }
 private void signatures(Document doc,String heading,String note,String[] labels,String[] names)throws DocumentException {
  var table=new PdfPTable(labels.length);table.setWidthPercentage(100);table.setSpacingBefore(28);table.setKeepTogether(true);
  for(int i=0;i<labels.length;i++){var cell=new PdfPCell();cell.setBorder(Rectangle.NO_BORDER);cell.setPadding(6);var label=new Paragraph(labels[i],bold);label.setAlignment(Element.ALIGN_CENTER);cell.addElement(label);var hint=new Paragraph("(Ký, ghi rõ họ tên)",small);hint.setAlignment(Element.ALIGN_CENTER);cell.addElement(hint);var name=new Paragraph(names[i].isBlank()?" ":names[i],body);name.setSpacingBefore(48);name.setAlignment(Element.ALIGN_CENTER);cell.addElement(name);table.addCell(cell);}var section=new PdfPTable(1);section.setWidthPercentage(100);section.setSpacingBefore(18);section.setKeepTogether(true);var content=new PdfPCell();content.setBorder(Rectangle.NO_BORDER);content.setPadding(0);content.addElement(new Paragraph(heading,bold));var description=new Paragraph(note,body);description.setSpacingBefore(10);content.addElement(description);content.addElement(table);section.addCell(content);doc.add(section);
 }
}
