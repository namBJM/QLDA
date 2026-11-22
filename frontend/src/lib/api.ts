export type Row = Record<string, any>;
let csrf = '';
const pending = new Map<string, string>();

/**
 * Người viết: Khuất Văn Nam
 * Ngày viết: 2026-11-03
 * Use case: Dùng chung cho các yêu cầu thay đổi dữ liệu
 *
 * Lấy token CSRF của phiên hiện tại và lưu cho các lệnh tiếp theo.
 * Sau đăng nhập cần lấy lại token để dùng token của phiên mới.
 */
export async function token() {
  const r = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
  const j = await r.json();
  csrf = j.token;
  return csrf;
}

/**
 * Người viết: Khuất Văn Nam
 * Ngày viết: 2026-11-04
 * Use case: Dùng chung cho các lệnh UC-CAT, UC-IN, UC-STK, UC-CNT, UC-OUT
 *
 * Gửi GET hoặc POST cùng cookie phiên, CSRF và khóa chống lặp.
 * Giữ khóa khi fetch lỗi mạng; xóa khóa khi đã nhận phản hồi HTTP. Phản hồi 401 phát sự kiện hết
 * phiên.
 */
export async function api(
  path: string,
  body?: unknown,
  key?: string,
): Promise<any> {
  const headers: Record<string, string> = {};
  if (body !== undefined) {
    if (!csrf) await token();
    headers['X-CSRF-TOKEN'] = csrf;
    const fingerprint = path + JSON.stringify(body);
    if (!pending.has(fingerprint))
      pending.set(fingerprint, crypto.randomUUID());
    headers['Idempotency-Key'] = key || pending.get(fingerprint)!;
    if (!(body instanceof FormData))
      headers['Content-Type'] = 'application/json';
  }
  const r = await fetch(path, {
    method: body === undefined ? 'GET' : 'POST',
    credentials: 'same-origin',
    headers,
    body:
      body === undefined
        ? undefined
        : body instanceof FormData
          ? body
          : JSON.stringify(body),
  });
  if (body !== undefined) pending.delete(path + JSON.stringify(body));
  if (!r.ok) {
    let j: Row = {};
    try {
      j = await r.json();
    } catch {}
    if (r.status === 401) window.dispatchEvent(new Event('session-expired'));
    throw new Error(j.message || `Không thực hiện được yêu cầu (${r.status}).`);
  }
  return r.status === 204 ? null : r.json();
}

/**
 * Người viết: Khuất Văn Nam
 * Ngày viết: 2026-11-05
 * Use case: Đăng nhập, dùng chung cho module M01
 *
 * Đăng nhập bằng biểu mẫu username/password có CSRF.
 * Lấy token trước đăng nhập và làm mới token sau khi đăng nhập thành công.
 */
export async function login(username: string, password: string) {
  await token();
  const r = await fetch('/api/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'X-CSRF-TOKEN': csrf,
    },
    body: new URLSearchParams({ username, password }),
  });
  if (!r.ok) throw new Error('Tên đăng nhập hoặc mật khẩu không đúng.');
  await token();
}

/**
 * Người viết: Khuất Văn Nam
 * Ngày viết: 2026-11-05
 * Use case: Đăng xuất, dùng chung cho module M01
 *
 * Kết thúc phiên đăng nhập và xóa token CSRF đã lưu.
 * Chỉ xóa token cục bộ sau khi yêu cầu đăng xuất hoàn tất.
 */
export async function logout() {
  await api('/api/auth/logout', {});
  csrf = '';
}
