import type {Row} from './lib/api';

const quantity=(value:unknown)=>value===null||value===undefined?'—':Number(value).toLocaleString('vi-VN',{maximumFractionDigits:3});
const date=(value:unknown)=>{if(!value)return '—';const parsed=new Date(String(value));return Number.isNaN(parsed.getTime())?'—':parsed.toLocaleString('vi-VN');};
const present=(value:unknown)=>value===null||value===undefined||value===''?'—':String(value);
const sourceNames:Record<string,string>={MANUAL:'Nhập thủ công',OPENING:'Tồn đầu kỳ'};

export default function ApprovalContent({approval,catalog}:{approval:Row,catalog:Row}){
 let payload:unknown=approval.payload;
 try{if(typeof payload==='string')payload=JSON.parse(payload);}catch{payload=null;}
 if(!payload||typeof payload!=='object')return <section className="snapshot"><h3>Nội dung đã trình</h3><p className="muted">Không đọc được nội dung hồ sơ. Vui lòng tải lại danh sách.</p></section>;
 const snapshot:Row=Array.isArray(payload)?{}:payload;
 const lookup=(kind:string,id:unknown)=>catalog[kind]?.find((row:Row)=>String(row.id)===String(id));
 const sku=(row:Row)=>{const item=lookup('skus',row.sku_id);return [row.sku_code||item?.code||`SKU #${row.sku_id}`,row.sku_name||item?.name].filter(Boolean).join(' · ');};
 const location=(row:Row)=>row.location_code||lookup('locations',row.location_id)?.code||(row.location_id?`Vị trí #${row.location_id}`:'—');
 const unit=(row:Row)=>{const item=lookup('skus',row.sku_id);const uom=lookup('uoms',item?.uom_id);return row.symbol||uom?.symbol||uom?.name||'—';};
 const lines:Row[]=Array.isArray(payload)?payload:Array.isArray(snapshot.lines)?snapshot.lines:[];
 const reservations:Row[]=Array.isArray(snapshot.reservations)?snapshot.reservations:[];
 const type=approval.receipt_id?'Phiếu nhập':approval.shipment_id?'Phiếu xuất':'Điều chỉnh tồn kho';
 const info:[string,unknown][]=[['Loại hồ sơ',type],['Mã phiếu',snapshot.code||`#${approval.receipt_id||approval.shipment_id||approval.adjustment_id}`],['Người trình',approval.submitted_by],['Thời gian trình',date(approval.submitted_at)],['Cấp duyệt',approval.level_code==='ACT02'?'Quản lý kho':approval.level_code==='ACT09'?'Lãnh đạo':approval.level_code]];
 if(snapshot.source_type)info.push(['Nguồn chứng từ',sourceNames[snapshot.source_type]||snapshot.source_type]);
 if(snapshot.source_ref)info.push(['Chứng từ nguồn',snapshot.source_ref]);
 if(snapshot.created_by)info.push(['Người lập',snapshot.created_by],['Ngày lập',date(snapshot.created_at)]);
 const reason=snapshot.discrepancy_draft?.reason||snapshot.reason||snapshot.issue_reason;
 if(reason)info.push(['Lý do / giải trình',reason]);
 const columns:[string,(row:Row)=>unknown][]=[['Dòng',row=>row.line_no],['Hàng hóa',sku]];
 if(approval.adjustment_id)columns.push(['Vị trí',location],['Chênh lệch tồn',row=>quantity(row.delta_book)],['Chênh lệch bị chặn',row=>quantity(row.delta_blocked)]);
 else{
  columns.push(['Đơn vị cơ sở',unit],[approval.receipt_id?'Dự kiến':'Yêu cầu',row=>quantity(approval.receipt_id?row.expected_qty:row.requested_qty)]);
  if(approval.receipt_id)columns.push(['Thực nhận',row=>quantity(row.received_qty)],['Chấp nhận',row=>quantity(row.accepted_qty)],['Bị chặn',row=>quantity(row.blocked_qty)],['Vị trí',location]);
 }
 const table=(items:Row[],fields:[string,(row:Row)=>unknown][])=> <div className="table-wrap"><table><thead><tr>{fields.map(([label])=><th key={label} scope="col">{label}</th>)}</tr></thead><tbody>{items.map((row,index)=><tr key={index}>{fields.map(([label,value])=><td key={label}>{present(value(row))}</td>)}</tr>)}</tbody></table></div>;
 return <section className="snapshot approval-content"><h3>Nội dung đã trình</h3><dl>{info.map(([label,value])=><div key={label}><dt>{label}</dt><dd>{present(value)}</dd></div>)}</dl>
  {lines.length>0&&<><h3>Danh sách hàng hóa</h3>{table(lines,columns)}<p className="muted">Số lượng tính theo đơn vị cơ sở.</p></>}
  {reservations.length>0&&<><h3>Phân bổ giữ hàng</h3>{table(reservations,[['Dòng',row=>row.line_no],['Hàng hóa',sku],['Vị trí',location],['Lượng giữ',row=>quantity(row.qty_held)],['Đã soạn',row=>quantity(row.qty_picked)],['Trạng thái',row=>({ACTIVE:'Đang giữ',RELEASED:'Đã giải phóng',CONSUMED:'Đã tiêu thụ'}[String(row.status)]||row.status)]])}</>}
  {snapshot.counted_qty!==undefined&&<><h3>Kết quả kiểm kê</h3><dl>{([['Người đếm',snapshot.counted_by],['Thời gian đếm',date(snapshot.counted_at)],['Tồn tại thời điểm đếm',quantity(snapshot.book_at_count)],['Số thực tế',quantity(snapshot.counted_qty)],['Chênh lệch',quantity(snapshot.delta)],['Ghi chú tình trạng',snapshot.condition_note]] as [string,unknown][]).map(([label,value])=><div key={label}><dt>{label}</dt><dd>{present(value)}</dd></div>)}</dl></>}
  <p className="muted">Thông tin được ghi nhận tại thời điểm trình duyệt.</p>
 </section>;
}
