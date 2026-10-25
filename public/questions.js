const byId = id => document.getElementById(id);
let rows = [];
let editing = 0;
const levels = {EASY: 'Dễ', MEDIUM: 'Trung bình', HARD: 'Khó'};
const letters = ['A', 'B', 'C', 'D'];
letters.forEach((letter, i) => {
    const label = document.createElement('label');
    label.textContent = `Đáp án ${letter}`;
    const input = document.createElement('input');
    input.id = `option-${i}`; input.required = true; input.maxLength = 1000;
    label.appendChild(input); byId('answers').appendChild(label);
});
async function request(method = 'GET', body) {
    const response = await fetch('/api/questions', {
        method, headers: {'Content-Type': 'application/json'},
        body: body ? JSON.stringify(body).replace(/[\u007f-\uffff]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0')) : undefined
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Không thể tải kho câu hỏi.');
    rows = data.questions;
    byId('editor').hidden = false; byId('library').hidden = false;
    render();
}
function render() {
    const query = byId('search').value.toLocaleLowerCase('vi');
    const filtered = rows.filter(q => q.text.toLocaleLowerCase('vi').includes(query) && (!byId('filter').value || q.difficulty === byId('filter').value));
    byId('count').textContent = `${filtered.length} / ${rows.length} câu hỏi`;
    byId('questions').replaceChildren();
    filtered.forEach(q => {
        const article = document.createElement('article');
        const heading = document.createElement('h3'); heading.textContent = q.text;
        const meta = document.createElement('small'); meta.textContent = `${q.topic} · ${levels[q.difficulty]} · Đúng: ${letters[q.correct]}`;
        const options = document.createElement('p'); options.textContent = q.options.map((o, i) => `${letters[i]}. ${o}`).join(' | ');
        const edit = document.createElement('button'); edit.textContent = 'Sửa'; edit.onclick = () => {
            editing = q.id; byId('text').value = q.text;
            ['correct', 'topic', 'difficulty'].forEach(key => byId(key).value = q[key]);
            q.options.forEach((o, i) => byId(`option-${i}`).value = o);
            byId('editor-title').textContent = `Sửa câu hỏi #${q.id}`;
            byId('text').focus(); byId('editor').scrollIntoView({behavior: 'smooth'});
        };
        const remove = document.createElement('button'); remove.textContent = 'Xóa'; remove.onclick = async () => {
            if (!confirm(`Xóa câu hỏi: ${q.text}?`)) return;
            try { await request('DELETE', {id: q.id}); byId('status').textContent = 'Đã xóa câu hỏi.'; }
            catch (e) { byId('status').textContent = e.message; }
        };
        article.append(heading, meta, options, edit, remove); byId('questions').appendChild(article);
    });
}
byId('search').oninput = render; byId('filter').onchange = render;
byId('new-question').onclick = () => { editing = 0; byId('question-form').reset(); byId('editor-title').textContent = 'Thêm câu hỏi'; };
byId('question-form').onsubmit = async event => {
    event.preventDefault();
    const button = event.submitter; button.disabled = true;
    try {
        await request('POST', {id: editing, text: byId('text').value.trim(), options: letters.map((_, i) => byId(`option-${i}`).value.trim()), correct: Number(byId('correct').value), topic: byId('topic').value, difficulty: byId('difficulty').value});
        byId('status').textContent = 'Đã lưu câu hỏi. Thay đổi áp dụng từ trận mới.';
        byId('new-question').click();
    } catch (e) { byId('status').textContent = e.message; }
    finally { button.disabled = false; }
};
request().catch(e => byId('status').textContent = e.message);
