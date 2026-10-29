import fs from 'node:fs';
const source = 'report/bao-cao-so-bo.md';
const original = fs.readFileSync(source, 'utf8');
if (!original.includes('## 19. Phụ lục sơ bộ')) throw new Error('Expected the original 19-section draft; refusing to restructure twice.');
fs.mkdirSync('report/ban-cu', { recursive: true });
fs.copyFileSync(source, 'report/ban-cu/bao-cao-so-bo-v1.md');
fs.copyFileSync('report/bao-cao-so-bo.docx', 'report/ban-cu/bao-cao-so-bo-v1.docx');
const sections = new Map([...original.matchAll(/^## (\d+)\. [^\n]+\n([\s\S]*?)(?=^## \d+\.|$(?![\s\S]))/gm)].map(m => [Number(m[1]), m[2].trim()]));
if (sections.size !== 19) throw new Error('Missing original sections.');
const get = n => sections.get(n);
const subhead = (text, old, current) => text.replace(new RegExp(`^### ${old}\\.(\\d+)\\. (.+)$`, 'gm'), `#### ${current}.$1. $2`);
const main = `# CHƯƠNG 1. TỔNG QUAN VỀ ĐỀ TÀI

## 1.1. Bối cảnh, mục tiêu và phạm vi

${get(4)}

## 1.2. Kiến thức nền và giải pháp liên quan

${get(5).replace('[Tài liệu Java SE 17](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html).', '(Oracle, không ghi năm).').replace('[RFC 6455](https://www.rfc-editor.org/rfc/rfc6455).', '(Fette & Melnikov, 2011).').replace('[Tài liệu SQLite WAL](https://www.sqlite.org/wal.html).', '(SQLite, không ghi năm).')}

## 1.3. Định nghĩa bài toán

${get(6)}

## 1.4. Thông tin thực hiện đề tài

${get(1).replace('Đây là bản sơ bộ;', 'Thể thức được điều chỉnh theo hướng dẫn ban hành kèm Quyết định 612/QĐ-ĐHHN (Trường Đại học Hà Nội, 2021). Đây là bản sơ bộ;')}

# CHƯƠNG 2. PHÂN TÍCH VÀ THIẾT KẾ HỆ THỐNG

## 2.1. Giải pháp đề xuất

${get(7)}

## 2.2. Kiến trúc hệ thống

${get(8).replace('```\n\nClient gồm', '```\n\n**Hình 2.1. Kiến trúc tổng thể của Game Portal**\n\nClient gồm')}

## 2.3. Thiết kế giao tiếp mạng

${subhead(get(9), 9, '2.3')}

# CHƯƠNG 3. TRIỂN KHAI HỆ THỐNG

${get(10).replace(/^### 10\.(\d+)\. (.+)$/gm, '## 3.$1. $2')}

# CHƯƠNG 4. KIỂM THỬ VÀ ĐÁNH GIÁ

## 4.1. Thiết lập thực nghiệm

${get(11)}

## 4.2. Kết quả sơ bộ

${subhead(get(12), 12, '4.2')}

## 4.3. Thảo luận kết quả

${get(13)}

## 4.4. Điểm cải tiến và đóng góp

${get(14)}

# CHƯƠNG 5. KẾT LUẬN VÀ HƯỚNG PHÁT TRIỂN

## 5.1. Kết quả đạt được

${get(17)}

## 5.2. Hạn chế

${get(15)}

## 5.3. Hướng phát triển

${get(16)}

# TÀI LIỆU THAM KHẢO

Fette, I., & Melnikov, A. (2011). *The WebSocket Protocol* (RFC 6455). Truy xuất từ https://www.rfc-editor.org/rfc/rfc6455

*Hướng dẫn thực hiện bài tập lớn – Lập trình mạng*. (2026). Tài liệu học phần năm học 2026–2027, file Instruction.md do người dùng cung cấp; chưa xác định tác giả từ tài liệu.

*Network Programming – Final Project*. (2026). Mẫu README học phần, file README.md do người dùng cung cấp; chưa xác định tác giả từ tài liệu.

Nhóm thực hiện Game Portal. (2026). *Mã nguồn, README và chương trình kiểm thử Game Portal* [Mã nguồn nội bộ]. Phiên bản đối chiếu ngày 28/10/2026. Minh chứng được dẫn tại chương 3 và mục 4.2.

Oracle. (không ghi năm). *Java SE 17 API – ServerSocket*. Truy xuất từ https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html

SQLite. (không ghi năm). *Write-Ahead Logging*. Truy xuất từ https://www.sqlite.org/wal.html

Trường Đại học Hà Nội. (2021). *Hướng dẫn thể thức trình bày luận án, luận văn và khóa luận tốt nghiệp của Trường Đại học Hà Nội* (ban hành kèm Quyết định số 612/QĐ-ĐHHN ngày 07/04/2021). File PDF do người dùng cung cấp.

# PHỤ LỤC

${get(19).replace(/^### ([A-F])\. (.+)$/gm, '## Phụ lục $1. $2').replace(/###/g, '##').replace('report/report.pdf', 'report/bao-cao-so-bo.pdf')}

# BẢN GIẢI TRÌNH CHỈNH SỬA

[Mẫu chờ bổ sung sau khi nhận ý kiến giảng viên hoặc hội đồng; không xác nhận đã có nhận xét hay phê duyệt.]

| STT | Ý kiến góp ý | Nội dung chỉnh sửa | Vị trí cập nhật |
| --- | --- | --- | --- |
| [Bổ sung] | [Bổ sung] | [Bổ sung] | [Bổ sung] |
`;
let content = main.replace(/mục 10 và 12/g, 'chương 3 và mục 4.2').replace(/mục 12/g, 'mục 4.2');
content = content.replace(/\| 1–3: Project Information, Summary, Overview \|[^\n]+/,'| 1–3: Project Information, Summary, Overview | Chương 1; mục 3.4 |')
    .replace(/\| 4–6: Architecture, Network, Technology \|[^\n]+/,'| 4–6: Architecture, Network, Technology | Chương 2–3 |')
    .replace(/\| 7: Novelty and Contributions \|[^\n]+/,'| 7: Novelty and Contributions | Mục 4.4 |')
    .replace(/\| 8–11: Structure, Requirements, Installation, Running \|[^\n]+/,'| 8–11: Structure, Requirements, Installation, Running | Chương 3; mục 4.1; phụ lục A–B |')
    .replace(/\| 12–15: Features, Setup, Results, Discussion \|[^\n]+/,'| 12–15: Features, Setup, Results, Discussion | Mục 3.4; chương 4 |')
    .replace(/\| 16–17: Limitations, Future Work \|[^\n]+/,'| 16–17: Limitations, Future Work | Mục 5.2–5.3 |')
    .replace(/\| 18–19: Report, References \|[^\n]+/,'| 18–19: Report, References | Báo cáo này; Tài liệu tham khảo |')
    .replace(/\| 22–24: Submission, Verification, Contact \|[^\n]+/,'| 22–24: Submission, Verification, Contact | Phụ lục F; bìa phụ và mục 1.4 |');
const captions = [
    'Bảng 1.1. Đặc tả bài toán', 'Bảng 1.2. Thông tin đề tài',
    'Bảng 3.1. Công nghệ sử dụng', 'Bảng 3.2. Các module và lớp chính',
    'Bảng 4.1. Kết quả kiểm thử chức năng', 'Bảng 4.2. Trạng thái đo hiệu năng',
    'Bảng 4.3. Các đóng góp kỹ thuật và minh chứng',
    'Bảng PL.1. Phân công thành viên', 'Bảng PL.2. Đối chiếu với mẫu README',
    'Bảng PL.3. Theo dõi nội dung chỉnh sửa',
];
let index = 0;
content = content.replace(/^(\|[^\n]+\n\|[\s:|\-]+\|)/gm, (match) => `**${captions[index++] || 'Bảng bổ sung'}**\n\n${match}`);
if (index !== captions.length) throw new Error(`Unexpected table count: ${index}`);
const front = `<!-- Báo cáo sơ bộ: áp dụng thể thức KLTN theo QĐ 612/QĐ-ĐHHN; loại tài liệu vẫn là báo cáo bài tập lớn. -->
<!-- REPORT:COVER -->
# TRANG BÌA CHÍNH

BỘ GIÁO DỤC VÀ ĐÀO TẠO

[TÊN TRƯỜNG]

[LOGO TRƯỜNG – 2,5 × 2,5 cm]

**BÁO CÁO BÀI TẬP LỚN**

**GAME PORTAL – CỔNG TRÒ CHƠI KIẾN THỨC TRỰC TUYẾN SỬ DỤNG JAVA SOCKET VÀ WEBSOCKET**

Môn học: Lập trình mạng

Người hướng dẫn: [Học hàm/học vị, họ và tên]

Sinh viên thực hiện: [Họ tên thành viên]

Mã số sinh viên: [Bổ sung]

Chuyên ngành: [Bổ sung]

Khoa: [Bổ sung]

[Địa điểm], năm 2026

<!-- REPORT:INNER_COVER -->
# TRANG BÌA PHỤ

Nội dung bìa phụ trình bày bằng tiếng Việt, tương ứng bìa chính. Thông tin trường, logo, giảng viên và thành viên được điền trong file report/thong-tin-bia.json trước khi xuất bản cuối.

<!-- REPORT:FRONT -->
# LỜI CAM ĐOAN

[Nhóm bổ sung lời cam đoan về phần việc thực tế, tính trung thực của dữ liệu và việc trích dẫn nguồn sau khi rà soát toàn bộ báo cáo. Đây là chỗ điền của bản sơ bộ, chưa phải cam đoan đã ký.]

[Địa điểm], ngày [..] tháng [..] năm [....]

Nhóm sinh viên thực hiện

[Họ tên và chữ ký]

# LỜI CẢM ƠN

[Nhóm bổ sung lời cảm ơn giảng viên hướng dẫn và các cá nhân, đơn vị đã hỗ trợ thực tế trong quá trình thực hiện đề tài.]

# MỤC LỤC

<!-- REPORT:TOC -->
Mục lục tự động được tạo trong bản Word theo các chương, mục và tiểu mục; số trang được cập nhật theo bố cục thực tế.

# DANH MỤC KÝ HIỆU, CHỮ VIẾT TẮT

| Ký hiệu | Diễn giải |
| --- | --- |
| API | Application Programming Interface – giao diện lập trình ứng dụng |
| CPU | Central Processing Unit – bộ xử lý trung tâm |
| HP | Health Points – chỉ số sinh lực trong trò chơi |
| HTTP | Hypertext Transfer Protocol – giao thức truyền tải siêu văn bản |
| JDBC | Java Database Connectivity – giao diện kết nối cơ sở dữ liệu của Java |
| JSON | JavaScript Object Notation – định dạng trao đổi dữ liệu |
| LAN | Local Area Network – mạng cục bộ |
| RAM | Random Access Memory – bộ nhớ truy cập ngẫu nhiên |
| SMTP | Simple Mail Transfer Protocol – giao thức chuyển thư |
| SQL | Structured Query Language – ngôn ngữ truy vấn dữ liệu |
| TCP | Transmission Control Protocol – giao thức điều khiển truyền vận |
| TLS | Transport Layer Security – bảo mật tầng truyền tải |
| UI | User Interface – giao diện người dùng |
| WAL | Write-Ahead Logging – cơ chế nhật ký ghi trước |
| WebSocket | Giao thức giao tiếp hai chiều giữa client và server |

# DANH MỤC BẢNG BIỂU

<!-- REPORT:TABLE_LIST -->
${captions.map(c => '- ' + c).join('\n')}

# DANH MỤC HÌNH VẼ

<!-- REPORT:FIGURE_LIST -->
- Hình 2.1. Kiến trúc tổng thể của Game Portal

# TÓM TẮT

${get(2)}

**Từ khóa:** ${get(3)}

<!-- REPORT:MAIN -->
`;
fs.writeFileSync(source, front + '\n' + content);
fs.writeFileSync('report/thong-tin-bia.json', JSON.stringify({
    ministry: 'BỘ GIÁO DỤC VÀ ĐÀO TẠO', school: '[TÊN TRƯỜNG]',
    documentType: 'BÁO CÁO BÀI TẬP LỚN', subject: 'Lập trình mạng',
    title: 'GAME PORTAL – CỔNG TRÒ CHƠI KIẾN THỨC TRỰC TUYẾN SỬ DỤNG JAVA SOCKET VÀ WEBSOCKET',
    instructor: '[Học hàm/học vị, họ và tên]', students: '[Họ tên thành viên]', studentIds: '[Bổ sung]',
    major: '[Bổ sung]', department: '[Bổ sung]', location: '[Địa điểm]', year: '2026', logoPath: ''
}, null, 2));
console.log('Restructured into 5 chapters; retained the original draft in report/ban-cu/.');
