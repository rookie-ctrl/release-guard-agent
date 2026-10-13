(() => {
    'use strict';

    const $ = id => document.getElementById(id);
    const storage = {
        get(key) { try { return localStorage.getItem(key); } catch { return null; } },
        set(key, value) { try { localStorage.setItem(key, value); } catch { /* Storage may be disabled. */ } },
        remove(key) { try { localStorage.removeItem(key); } catch { /* Keep the current session usable. */ } }
    };
    const types = { GENERAL: '通用文档', RELEASE_POLICY: '发布规范', INCIDENT: '历史事故', RUNBOOK: '运维手册' };
    const statuses = { PENDING: '等待解析', PARSING: '解析中', SUCCESS: '已入库', FAILED: '解析失败' };
    const number = value => Number(value || 0).toLocaleString('zh-CN');
    const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
    const icon = name => `<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const endpoints = { docs: null, stats: null };
    let documents = [];
    let documentsLoaded = false;
    let docsLoading = false;
    let docsSignature = '';
    let activeFilter = 'ALL';
    let selectedFile = null;
    let uploading = false;
    let deletingDoc = null;
    let previewRequest = 0;
    let focusedScrollPosition = 0;
    let sessionId = storage.get('releaseGuardSessionId');
    let activeEventSource = null;
    let assistantMessage = null;
    let assistantText = '';
    let restoredAssistant = null;
    let busy = false;
    let initializing = true;
    let statsLoading = false;
    const confirmationCards = new Map();

    async function request(url, options = {}) {
        let response;
        try {
            response = await fetch(url, { ...options, signal: AbortSignal.timeout(30000) });
        } catch (error) {
            throw new Error(error.name === 'TimeoutError' ? '请求超时，请稍后重试。' : '暂时无法连接服务，请检查后端是否已启动。');
        }
        const text = await response.text();
        let data = null;
        try { data = text ? JSON.parse(text) : null; } catch { /* Handle a non-JSON server error below. */ }
        if (!response.ok) {
            const error = new Error(data?.error || data?.message || `请求未成功（${response.status}），请稍后重试。`);
            error.status = response.status;
            throw error;
        }
        if (text && data === null) throw new Error('服务返回了无法识别的数据，请检查后端连接。');
        return data;
    }
    const postJson = (url, body) => request(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

    function toast(message, error = false) {
        const card = document.createElement('div');
        card.className = 'toast' + (error ? ' error' : '');
        card.innerHTML = icon(error ? 'alert' : 'check') + `<span>${escapeHtml(message)}</span><button class="icon-button" aria-label="关闭提示">${icon('close')}</button>`;
        card.querySelector('button').onclick = () => card.remove();
        $('toastContainer').append(card);
        while ($('toastContainer').children.length > 3) $('toastContainer').firstElementChild.remove();
        window.setTimeout(() => card.remove(), error ? 8000 : 5000);
    }

    function updateConnection() {
        const { docs, stats } = endpoints;
        const online = docs === true && stats === true;
        const partial = (docs === true || stats === true) && (docs === false || stats === false);
        const failed = docs === false || stats === false;
        $('connectionStatus').className = 'connection-status' + (online ? ' online' : failed ? ' offline' : '');
        $('connectionText').textContent = online ? '工作空间已连接' : partial ? '部分服务不可用' : failed ? '服务未连接' : '正在连接';
        $('connectionStatus').title = online ? '知识库与用量接口可用' : failed ? '请确认 ReleaseGuard 后端和依赖服务已启动' : '正在读取工作空间数据';
    }

    function formatSize(bytes) {
        if (bytes == null) return '';
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB';
    }

    function emptyLibrary(title, description, retry = false) {
        $('docList').innerHTML = `<div class="library-empty">${icon(retry ? 'retry' : 'book')}<strong>${escapeHtml(title)}</strong><p>${escapeHtml(description)}</p>${retry ? '<button class="text-button" id="retryLibraryBtn">重新连接</button>' : ''}</div>`;
        if (retry) $('retryLibraryBtn').onclick = () => loadDocs(true);
    }

    function renderDocs() {
        if (!documentsLoaded) return;
        const query = $('docSearch').value.trim().toLowerCase();
        const filtered = documents.filter(doc => (activeFilter === 'ALL' || doc.documentType === activeFilter)
            && `${doc.docName} ${doc.serviceName || ''}`.toLowerCase().includes(query));
        $('listCount').textContent = filtered.length === documents.length ? `(${documents.length})` : `(${filtered.length}/${documents.length})`;
        if (!filtered.length) {
            emptyLibrary(documents.length ? '没有找到匹配文档' : '知识，正在等你补充', documents.length ? '试试其他关键词或文档类型。' : '上传一份规范，让下一次评审更有依据。');
            return;
        }
        const fragment = document.createDocumentFragment();
        for (const doc of filtered) {
            const row = document.createElement('article');
            row.className = 'doc-item';
            const status = Object.hasOwn(statuses, doc.status) ? doc.status : 'PENDING';
            const meta = [types[doc.documentType] || '通用文档', doc.serviceName, formatSize(doc.fileSize)].filter(Boolean);
            const progress = doc.parsedChunks != null && (status === 'PARSING' || status === 'FAILED')
                ? `已入库 ${number(doc.parsedChunks)} 块` : status === 'SUCCESS' && doc.chunkCount != null ? `${number(doc.chunkCount)} 个片段` : '';
            row.innerHTML = `<span class="doc-icon">${icon('file')}</span><div class="doc-info"><button class="doc-name doc-preview-btn" type="button" aria-label="查看文档 ${escapeHtml(doc.docName)}" title="点击查看文档内容">${escapeHtml(doc.docName)}</button><div class="doc-meta">${meta.map(escapeHtml).join('<span>·</span>')}</div><div class="doc-status-row"><span class="badge ${status}">${statuses[status]}</span><span class="progress">${escapeHtml(progress)}</span></div><button class="doc-open-button preview-btn" type="button" aria-label="查看内容 ${escapeHtml(doc.docName)}">${icon('book')}<span>查看内容</span>${icon('arrow')}</button></div><div class="doc-actions">${status === 'FAILED' ? `<button class="icon-button retry-btn" aria-label="重试解析 ${escapeHtml(doc.docName)}" title="从断点重试">${icon('retry')}</button>` : ''}<button class="icon-button del-btn" aria-label="删除 ${escapeHtml(doc.docName)}" title="删除文档">${icon('trash')}</button></div>`;
            row.querySelector('.doc-preview-btn').onclick = () => openDocument(doc);
            row.querySelector('.preview-btn').onclick = () => openDocument(doc);
            row.onclick = event => {
                if (event.target.closest('button, a, .doc-actions')) return;
                void openDocument(doc);
            };
            if (status === 'FAILED' && doc.errorMsg) row.querySelector('.badge').title = doc.errorMsg;
            row.querySelector('.del-btn').onclick = () => {
                deletingDoc = doc;
                $('deleteDescription').textContent = doc.docName;
                $('deleteDialog').showModal();
            };
            const retry = row.querySelector('.retry-btn');
            if (retry) retry.onclick = async () => {
                retry.disabled = true;
                try {
                    await request(`/api/documents/${encodeURIComponent(doc.id)}/retry`, { method: 'POST' });
                    toast('已提交重新解析，将从已有进度继续。');
                    docsSignature = '';
                    await loadDocs();
                } catch (error) { toast(error.message, true); } finally { retry.disabled = false; }
            };
            fragment.append(row);
        }
        $('docList').replaceChildren(fragment);
    }

    async function loadDocs(manual = false) {
        if (docsLoading) return;
        docsLoading = true;
        $('refreshDocsBtn').disabled = true;
        try {
            const docs = await request('/api/documents');
            if (!Array.isArray(docs)) throw new Error('知识库数据格式不正确。');
            const signature = JSON.stringify(docs);
            const changed = signature !== docsSignature || !documentsLoaded;
            documents = docs;
            documentsLoaded = true;
            docsSignature = signature;
            for (const id of ['knowledgeCount', 'navDocCount', 'documentCount']) $(id).textContent = number(docs.length);
            $('usageDocs').textContent = number(docs.length) + ' 份';
            if (changed) renderDocs();
            endpoints.docs = true;
        } catch (error) {
            endpoints.docs = false;
            if (!documentsLoaded) emptyLibrary('知识库暂时未连接', '启动后端服务后，可在这里管理文档。', true);
            if (manual) toast(error.message, true);
        } finally {
            docsLoading = false;
            $('refreshDocsBtn').disabled = false;
            updateConnection();
        }
    }

    function setDocumentView(raw) {
        $('documentContent').hidden = raw;
        $('documentRaw').hidden = !raw;
        $('documentReadBtn').classList.toggle('active', !raw);
        $('documentRawBtn').classList.toggle('active', raw);
        $('documentReadBtn').setAttribute('aria-pressed', String(!raw));
        $('documentRawBtn').setAttribute('aria-pressed', String(raw));
    }

    async function openDocument(doc) {
        const requestId = ++previewRequest;
        $('documentTitle').textContent = doc.docName;
        $('documentDescription').textContent = [types[doc.documentType] || '通用文档', doc.serviceName,
            formatSize(doc.fileSize), statuses[doc.status]].filter(Boolean).join(' · ');
        $('documentDownload').href = `/api/documents/${encodeURIComponent(doc.id)}/download`;
        $('documentPreviewNote').textContent = '正在加载文档内容…';
        $('documentPreviewNote').classList.remove('error');
        $('documentContent').replaceChildren();
        $('documentRaw').textContent = '';
        $('documentContent').setAttribute('aria-busy', 'true');
        setDocumentView(false);
        $('documentDialog').showModal();
        try {
            const data = await request(`/api/documents/${encodeURIComponent(doc.id)}/content`);
            if (requestId !== previewRequest || !$('documentDialog').open) return;
            if (!data || typeof data.content !== 'string') throw new Error('文档内容格式不正确。');
            $('documentRaw').textContent = data.content;
            if (data.format === 'markdown') $('documentContent').innerHTML = markdown(data.content);
            else {
                const text = document.createElement('pre');
                text.className = 'document-plain';
                text.textContent = data.content;
                $('documentContent').replaceChildren(text);
            }
            $('documentPreviewNote').textContent = !data.content.trim() ? '没有提取到可预览的文字，可下载原文件查看。'
                : data.truncated ? '文档较长，仅预览前 100,000 个字符。请下载原文件查看完整内容。'
                    : data.format === 'markdown' ? 'Markdown 阅读预览 · 可切换原始文本。'
                        : '文本预览 · PDF / Word 展示提取的文字，原始排版请下载查看。';
        } catch (error) {
            if (requestId !== previewRequest || !$('documentDialog').open) return;
            $('documentPreviewNote').textContent = error.message;
            $('documentPreviewNote').classList.add('error');
        } finally {
            if (requestId === previewRequest) $('documentContent').setAttribute('aria-busy', 'false');
        }
    }

    $('documentReadBtn').onclick = () => setDocumentView(false);
    $('documentRawBtn').onclick = () => setDocumentView(true);
    $('documentDialog').addEventListener('close', () => { previewRequest++; });

    function selectFile(file) {
        if (uploading || !file) return;
        if (!/\.(pdf|docx?|md|txt)$/i.test(file.name)) return toast('请选择 PDF、Word、Markdown 或 TXT 文档。', true);
        if (file.size === 0) return toast('文档为空，请选择有内容的文件。', true);
        if (file.size > 50 * 1024 * 1024) return toast('文档大小不能超过 50 MB。', true);
        selectedFile = file;
        $('fileName').textContent = file.name;
        $('fileHint').textContent = formatSize(file.size) + ' · 点击可重新选择';
        $('dropzone').classList.add('has-file');
        $('uploadBtn').disabled = false;
    }

    function resetFile() {
        selectedFile = null;
        $('fileInput').value = '';
        $('fileName').textContent = '点击上传，或拖入文档';
        $('fileHint').textContent = 'PDF、Word、Markdown、TXT · 最大 50 MB';
        $('dropzone').classList.remove('has-file');
        $('uploadBtn').disabled = true;
    }

    $('fileInput').onchange = () => selectFile($('fileInput').files[0]);
    $('dropzone').onkeydown = event => {
        if ((event.key === 'Enter' || event.key === ' ') && !uploading) { event.preventDefault(); $('fileInput').click(); }
    };
    for (const type of ['dragenter', 'dragover']) $('dropzone').addEventListener(type, event => {
        event.preventDefault();
        if (!uploading) $('dropzone').classList.add('dragging');
    });
    $('dropzone').ondragleave = event => {
        if (!$('dropzone').contains(event.relatedTarget)) $('dropzone').classList.remove('dragging');
    };
    $('dropzone').ondrop = event => {
        event.preventDefault();
        $('dropzone').classList.remove('dragging');
        if (event.dataTransfer.files.length !== 1) return toast('每次请上传一份文档。', true);
        selectFile(event.dataTransfer.files[0]);
    };
    // Prevent a dropped file outside the upload area from navigating away from the review.
    for (const type of ['dragover', 'drop']) window.addEventListener(type, event => {
        if (Array.from(event.dataTransfer?.types || []).includes('Files')) event.preventDefault();
    });

    $('uploadBtn').onclick = async () => {
        if (!selectedFile || uploading) return;
        uploading = true;
        for (const id of ['uploadBtn', 'fileInput', 'documentType', 'serviceName']) $(id).disabled = true;
        $('dropzone').setAttribute('aria-disabled', 'true');
        $('uploadButtonText').textContent = '正在上传…';
        try {
            const form = new FormData();
            form.append('file', selectedFile);
            form.append('documentType', $('documentType').value);
            form.append('serviceName', $('serviceName').value.trim());
            await request('/api/documents', { method: 'POST', body: form });
            resetFile();
            toast('文档已上传，正在等待解析。');
            await loadDocs();
            await loadStats();
        } catch (error) { toast(error.message, true); } finally {
            uploading = false;
            for (const id of ['fileInput', 'documentType', 'serviceName']) $(id).disabled = false;
            $('uploadBtn').disabled = !selectedFile;
            $('dropzone').removeAttribute('aria-disabled');
            $('uploadButtonText').textContent = '添加到知识库';
        }
    };

    $('confirmDeleteBtn').onclick = async () => {
        if (!deletingDoc) return;
        const doc = deletingDoc;
        $('confirmDeleteBtn').disabled = true;
        try {
            await request(`/api/documents/${encodeURIComponent(doc.id)}`, { method: 'DELETE' });
            $('deleteDialog').close();
            deletingDoc = null;
            toast('文档已从知识库移除。');
            await loadDocs();
            await loadStats();
        } catch (error) { toast(error.message, true); } finally { $('confirmDeleteBtn').disabled = false; }
    };
    $('docSearch').oninput = renderDocs;
    $('refreshDocsBtn').onclick = () => loadDocs(true);
    document.querySelectorAll('[data-filter]').forEach(button => {
        button.onclick = () => {
            activeFilter = button.dataset.filter;
            document.querySelectorAll('[data-filter]').forEach(item => {
                const active = item === button;
                item.classList.toggle('active', active);
                item.setAttribute('aria-pressed', String(active));
            });
            renderDocs();
        };
    });

    // Escape all input before producing the small supported Markdown subset.
    // Raw HTML and non-HTTP(S) links never become executable markup.
    function inlineMarkdown(value) {
        const codes = [];
        let result = escapeHtml(value).replace(/`([^`]+)`/g, (_, code) => {
            const index = codes.push(`<code>${code}</code>`) - 1;
            return `\u0000${index}\u0000`;
        });
        result = result.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/\*([^*\n]+)\*/g, '<em>$1</em>');
        return result.replace(/\u0000(\d+)\u0000/g, (_, index) => codes[Number(index)] || '');
    }

    function markdown(text) {
        const lines = String(text || '').replace(/\r\n?/g, '\n').split('\n');
        const output = [];
        let index = 0;
        const cells = line => line.trim().replace(/^\||\|$/g, '').split('|').map(cell => cell.trim());
        const tableDivider = line => /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$/.test(line.trim());
        while (index < lines.length) {
            const line = lines[index];
            if (!line.trim()) { index++; continue; }
            if (/^\s*```/.test(line)) {
                const code = [];
                index++;
                while (index < lines.length && !/^\s*```/.test(lines[index])) code.push(lines[index++]);
                if (index < lines.length) index++;
                output.push(`<pre><code>${escapeHtml(code.join('\n'))}</code></pre>`);
                continue;
            }
            if (index + 1 < lines.length && line.includes('|') && tableDivider(lines[index + 1])) {
                const header = cells(line);
                index += 2;
                const rows = [];
                while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
                    const row = cells(lines[index++]);
                    rows.push('<tr>' + header.map((_, col) => `<td>${inlineMarkdown(row[col] || '')}</td>`).join('') + '</tr>');
                }
                output.push('<div class="table-scroll"><table><thead><tr>' + header.map(cell => `<th>${inlineMarkdown(cell)}</th>`).join('') + '</tr></thead><tbody>' + rows.join('') + '</tbody></table></div>');
                continue;
            }
            const heading = line.match(/^#{1,6}\s+(.+)$/);
            if (heading) { output.push(`<h3>${inlineMarkdown(heading[1])}</h3>`); index++; continue; }
            if (/^\s*(---+|\*\*\*+)\s*$/.test(line)) { output.push('<hr>'); index++; continue; }
            const list = line.match(/^\s*(?:[-*+]\s+|\d+[.)]\s+)(.*)$/);
            if (list) {
                const ordered = /^\s*\d+[.)]/.test(line);
                const pattern = ordered ? /^\s*\d+[.)]\s+(.*)$/ : /^\s*[-*+]\s+(.*)$/;
                const items = [];
                while (index < lines.length) {
                    const item = lines[index].match(pattern);
                    if (!item) break;
                    items.push(`<li>${inlineMarkdown(item[1])}</li>`);
                    index++;
                }
                const tag = ordered ? 'ol' : 'ul';
                output.push(`<${tag}>${items.join('')}</${tag}>`);
                continue;
            }
            if (/^>\s?/.test(line)) { output.push(`<blockquote>${inlineMarkdown(line.replace(/^>\s?/, ''))}</blockquote>`); index++; continue; }
            output.push(`<p>${inlineMarkdown(line)}</p>`);
            index++;
        }
        return output.join('');
    }

    function scrollChat(force = false) {
        const area = $('chatBody');
        if (force || area.scrollHeight - area.scrollTop - area.clientHeight < 160) area.scrollTop = area.scrollHeight;
    }

    function addMessage(text, kind = 'bot') {
        const follow = $('chatBody').scrollHeight - $('chatBody').scrollTop - $('chatBody').clientHeight < 160;
        $('welcome').hidden = true;
        const row = document.createElement('article');
        row.className = 'message ' + kind;
        const time = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai' });
        row.innerHTML = `<span class="message-avatar">${kind === 'user' ? '你' : icon(kind === 'error' ? 'alert' : 'shield')}</span><div class="message-content"><div class="message-label"><span>${kind === 'user' ? '你' : 'ReleaseGuard'}</span><span class="message-time">${time}</span></div><div class="message-text"></div></div>`;
        const content = row.querySelector('.message-text');
        if (kind === 'bot') content.innerHTML = markdown(text);
        else content.textContent = text;
        $('messageList').append(row);
        scrollChat(follow || kind === 'user');
        return content;
    }

    const toolNames = {
        searchKnowledge: '检索团队知识', githubPullRequest: '读取 PR 信息',
        githubFiles: '读取变更文件', githubDiff: '读取代码变更',
        githubFileContext: '读取文件上下文', githubChecks: '检查 CI 状态',
        getReleaseInfo: '读取发布信息', getTestReport: '检查测试报告',
        getCodeChangeSummary: '分析代码变更', getDatabaseChanges: '检查数据库变更',
        getServiceDependencies: '检查服务依赖', getServiceMetrics: '查看监控信息',
        createReleaseApproval: '创建发布审批'
    };
    const toolName = name => toolNames[name] || name || '评审工具';

    function addActivity(title, data) {
        $('welcome').hidden = true;
        const follow = $('chatBody').scrollHeight - $('chatBody').scrollTop - $('chatBody').clientHeight < 160;
        const details = document.createElement('details');
        details.className = 'activity';
        const summary = document.createElement('summary');
        summary.textContent = title;
        const pre = document.createElement('pre');
        const serialized = typeof data === 'string' ? data : JSON.stringify(data ?? {}, null, 2);
        pre.textContent = serialized.length > 24000 ? serialized.slice(0, 24000) + '\n… 展示内容已截断' : serialized;
        details.append(summary, pre);
        $('messageList').append(details);
        scrollChat(follow);
        return details;
    }

    function setRunStatus(text, state = '') {
        $('runStatus').className = 'run-status' + (state ? ' ' + state : '');
        $('runStatusText').textContent = text;
    }

    function setSteps(stage) {
        for (let index = 1; index <= 3; index++) $('step' + index).classList.toggle('active', index <= stage);
    }

    function setBusy(value) {
        busy = value;
        for (const id of ['sendBtn', 'startReviewBtn', 'demoSessionBtn', 'pullRequestInput', 'newReviewBtn']) $(id).disabled = value;
        $('chatBody').setAttribute('aria-busy', String(value));
        document.querySelectorAll('.suggestion').forEach(button => { button.disabled = value; });
    }

    function showSession(session) {
        if (session.mode === 'GITHUB_PR') {
            const context = JSON.parse(session.pullRequestContextJson);
            $('pullRequestInput').value = context.url;
            $('currentReview').textContent = `当前 PR：${context.url} · 提交 ${String(context.headSha || '').slice(0, 12)}`;
        } else {
            $('pullRequestInput').value = '';
            $('currentReview').textContent = '演示评审 · order-service v2.3.7 · 使用预设发布数据';
        }
        $('currentReview').hidden = false;
        setSteps(session.status === 'COMPLETED' ? 3 : 1);
        setRunStatus(session.status === 'COMPLETED' ? '评审已完成' : session.status === 'FAILED' ? '上次评审失败' : '可以继续追问', session.status === 'COMPLETED' ? 'complete' : session.status === 'FAILED' ? 'error' : '');
    }

    function clearConversation() {
        $('messageList').replaceChildren();
        $('welcome').hidden = false;
        assistantMessage = null;
        assistantText = '';
        restoredAssistant = null;
        confirmationCards.clear();
    }

    async function createReviewSession(url) {
        const session = await postJson('/api/agent/sessions', { userId: 'demo-user', pullRequestUrl: url || null });
        if (!session?.sessionId) throw new Error('创建评审会话失败，请重试。');
        if (activeEventSource) activeEventSource.close();
        activeEventSource = null;
        sessionId = session.sessionId;
        storage.set('releaseGuardSessionId', sessionId);
        storage.remove('releaseGuardRunId');
        clearConversation();
        showSession(session);
    }

    async function initializeSession() {
        if (!sessionId) return;
        try {
            const session = await request('/api/agent/sessions/' + encodeURIComponent(sessionId));
            showSession(session);
            const history = await request('/api/agent/sessions/' + encodeURIComponent(sessionId) + '/messages');
            if (Array.isArray(history)) history.filter(message => ['USER', 'ASSISTANT'].includes(message.role)).forEach(message => {
                const element = addMessage(message.content, message.role === 'USER' ? 'user' : 'bot');
                // MessageResponse has no timestamp; do not label history with the current time.
                element.parentElement.querySelector('.message-time').remove();
                if (message.role === 'ASSISTANT') restoredAssistant = { text: message.content, element };
                else restoredAssistant = null;
            });
            scrollChat(true);
            const activeRunId = storage.get('releaseGuardRunId');
            if (activeRunId) { setBusy(true); subscribeRun(activeRunId); }
        } catch (error) {
            if (error.status === 404 || error.status === 400 || error.status === 403) {
                sessionId = null;
                storage.remove('releaseGuardSessionId');
                storage.remove('releaseGuardRunId');
                toast('上次会话已失效，可以开始新的评审。');
            } else {
                // Keep the session key when the server is temporarily unavailable.
                setRunStatus('会话恢复失败', 'error');
                toast('未能恢复上次评审：' + error.message, true);
                sessionId = null;
            }
        }
    }

    function resizeComposer() {
        $('questionInput').style.height = 'auto';
        $('questionInput').style.height = Math.min(124, Math.max(30, $('questionInput').scrollHeight)) + 'px';
    }

    async function send(allowBusy = false) {
        if (initializing || (busy && !allowBusy)) return;
        const question = $('questionInput').value.trim();
        if (!question) return;
        if (!sessionId) {
            toast('先粘贴 PR 链接开始评审，或点击「体验演示」。');
            $('pullRequestInput').focus();
            return;
        }
        setBusy(true);
        setRunStatus('正在提交', 'busy');
        try {
            const run = await postJson('/api/agent/sessions/' + encodeURIComponent(sessionId) + '/messages', { message: question });
            if (!run?.runId) throw new Error('提交评审失败，请重试。');
            addMessage(question, 'user');
            $('questionInput').value = '';
            resizeComposer();
            restoredAssistant = null;
            storage.set('releaseGuardRunId', run.runId);
            subscribeRun(run.runId);
        } catch (error) {
            // Preserve the draft so a failed request can be retried without retyping.
            addMessage(error.message, 'error');
            setRunStatus('提交失败', 'error');
            setBusy(false);
        }
    }

    function subscribeRun(runId) {
        if (activeEventSource) activeEventSource.close();
        assistantMessage = null;
        assistantText = '';
        confirmationCards.clear();
        setRunStatus('正在评审', 'busy');
        setSteps(1);
        const source = new EventSource('/api/agent/runs/' + encodeURIComponent(runId) + '/events');
        activeEventSource = source;
        const listen = (type, handler) => source.addEventListener(type, event => {
            if (activeEventSource !== source) return;
            try { handler(event.data ? JSON.parse(event.data) : {}); } catch {
                addMessage('评审事件无法解析，请重新连接后查看。', 'error');
                finishRun(false);
            }
        });
        source.onopen = () => { if (activeEventSource === source) setRunStatus('正在评审', 'busy'); };
        listen('runStarted', () => { setRunStatus('正在分析变更', 'busy'); setSteps(1); });
        listen('plan', data => addMessage(data.message, 'notice'));
        listen('toolCall', data => {
            if (data.tool === 'searchKnowledge') { setSteps(2); setRunStatus('正在关联知识', 'busy'); }
            else setRunStatus('正在收集依据', 'busy');
            addActivity('正在' + toolName(data.tool) + (data.riskLevel === 'HIGH_RISK' ? ' · 需要确认' : ''), data.arguments);
        });
        listen('toolResult', data => {
            addActivity(toolName(data.tool) + ' · 已返回' + (data.durationMs != null ? ` · ${number(data.durationMs)} ms` : ''), data.result);
        });
        listen('confirmation', showConfirmation);
        listen('confirmationApproved', () => {
            for (const card of confirmationCards.values()) {
                card.querySelectorAll('button').forEach(button => { button.disabled = true; });
                card.querySelector('.confirmation-result').textContent = '已批准，正在创建审批单。';
            }
            setRunStatus('正在创建审批单', 'busy');
        });
        listen('approvalUncertain', () => {
            for (const card of confirmationCards.values()) {
                card.querySelector('.confirmation-result').textContent = '审批结果尚未确认，正在核对，请勿重复创建。';
            }
            setRunStatus('正在核对审批结果', 'waiting');
        });
        listen('confirmationResolved', data => {
            for (const card of confirmationCards.values()) {
                card.dataset.resolved = 'true';
                card.querySelectorAll('button').forEach(button => { button.disabled = true; });
                card.querySelector('.confirmation-result').textContent = data.failed ? '审批系统拒绝执行，继续生成评审建议。' : data.approved ? '审批单已创建，继续评审。' : '已拒绝操作，继续生成评审建议。';
            }
            addMessage(data.failed ? '审批系统拒绝执行：' + data.message : data.approved ? '审批单已创建，继续完成评审。' : '已拒绝审批操作，Agent 将继续给出结论。', 'notice');
            setRunStatus('继续评审中', 'busy');
        });
        listen('token', data => {
            if (typeof data.text !== 'string') return;
            const follow = $('chatBody').scrollHeight - $('chatBody').scrollTop - $('chatBody').clientHeight < 160;
            if (!assistantMessage && restoredAssistant?.text === data.text) {
                assistantMessage = restoredAssistant.element;
                assistantText = restoredAssistant.text;
            } else {
                if (!assistantMessage) assistantMessage = addMessage('');
                assistantText += data.text;
                assistantMessage.innerHTML = markdown(assistantText);
            }
            restoredAssistant = null;
            setSteps(3);
            setRunStatus('正在整理建议', 'busy');
            scrollChat(follow);
        });
        listen('runCompleted', () => finishRun(true));
        listen('runFailed', data => { addMessage(data.message || '评审未能完成，请稍后重试。', 'error'); finishRun(false); });
        source.onerror = () => {
            if (activeEventSource !== source) return;
            if (source.readyState === EventSource.CLOSED) {
                addMessage('评审连接已关闭。请刷新恢复会话，再检查执行结果。', 'error');
                finishRun(false, true);
            } else setRunStatus('连接中断，正在重连', 'waiting');
        };
    }

    function showConfirmation(data) {
        if (confirmationCards.has(data.confirmationId)) return;
        $('welcome').hidden = true;
        const card = document.createElement('section');
        card.className = 'confirmation-card';
        card.innerHTML = `<div class="confirmation-title">${icon('alert')}这一步，需要你的确认</div><p>Agent 请求执行：${escapeHtml(toolName(data.tool))}</p><pre>${escapeHtml(JSON.stringify(data.arguments, null, 2))}</pre><div class="confirmation-actions"><button class="button primary approve">确认执行</button><button class="button secondary reject">拒绝操作</button></div><p class="confirmation-result" role="status"></p>`;
        const approve = card.querySelector('.approve');
        const reject = card.querySelector('.reject');
        const decide = async approved => {
            const source = activeEventSource;
            approve.disabled = true;
            reject.disabled = true;
            card.querySelector('.confirmation-result').textContent = '正在提交决定…';
            try {
                await postJson('/api/agent/confirmations/' + encodeURIComponent(data.confirmationId) + '/decision', { token: data.token, approved });
                // SSE can complete the run before the HTTP response arrives.
                if (activeEventSource === source && card.dataset.resolved !== 'true') {
                    card.querySelector('.confirmation-result').textContent = approved ? '决定已提交，等待执行结果。' : '已拒绝操作，等待后续建议。';
                    setRunStatus('继续评审中', 'busy');
                }
            } catch (error) {
                card.querySelector('.confirmation-result').textContent = error.message;
                if (activeEventSource === source && card.dataset.resolved !== 'true') {
                    approve.disabled = false;
                    reject.disabled = false;
                }
            }
        };
        approve.onclick = () => decide(true);
        reject.onclick = () => decide(false);
        confirmationCards.set(data.confirmationId, card);
        $('messageList').append(card);
        setRunStatus('等待你的确认', 'waiting');
        scrollChat(true);
    }

    function finishRun(success, keepRun = false) {
        if (activeEventSource) activeEventSource.close();
        activeEventSource = null;
        setBusy(false);
        setRunStatus(success ? '评审已完成' : keepRun ? '连接已中断' : '评审未完成', success ? 'complete' : 'error');
        if (success) setSteps(3);
        if (!keepRun) storage.remove('releaseGuardRunId');
        for (const card of confirmationCards.values()) card.querySelectorAll('button').forEach(button => { button.disabled = true; });
        void loadStats();
    }

    function setPrError(message = '') {
        $('prError').textContent = message;
        $('prError').hidden = !message;
        $('pullRequestInput').setAttribute('aria-invalid', String(Boolean(message)));
    }

    async function startReview(demo = false) {
        if (busy || initializing) return;
        let url = $('pullRequestInput').value.trim();
        if (!demo) {
            try {
                const parsed = new URL(url);
                if (parsed.protocol !== 'https:' || parsed.hostname !== 'github.com' || parsed.port || parsed.username || parsed.password || !/^\/[^/]+\/[^/]+\/pull\/[1-9]\d*\/?$/.test(parsed.pathname)) throw new Error();
                url = parsed.origin + parsed.pathname.replace(/\/$/, '');
            } catch {
                setPrError('请填写公开 GitHub PR 链接，例如 https://github.com/owner/repo/pull/123');
                $('pullRequestInput').focus();
                return;
            }
        }
        setPrError();
        setBusy(true);
        setRunStatus('正在创建评审', 'busy');
        const draft = $('questionInput').value.trim();
        try {
            await createReviewSession(demo ? null : url);
            $('questionInput').value = draft || (demo ? '帮我评审 order-service v2.3.7 能不能发布'
                : '请评审这个 PR，读取变更与 CI，并结合知识库规范给出风险、代码依据、改进建议和未确认事项。');
            resizeComposer();
            await send(true);
        } catch (error) {
            toast(error.message, true);
            setRunStatus('创建失败', 'error');
            setBusy(false);
        }
    }

    $('reviewForm').onsubmit = event => { event.preventDefault(); void startReview(); };
    $('demoSessionBtn').onclick = () => startReview(true);
    $('pullRequestInput').oninput = () => setPrError();
    $('chatForm').onsubmit = event => { event.preventDefault(); void send(); };
    $('questionInput').oninput = resizeComposer;
    $('questionInput').onkeydown = event => {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); void send(); }
    };
    document.querySelectorAll('[data-question]').forEach(button => {
        button.onclick = () => {
            if (busy) return;
            $('questionInput').value = button.dataset.question;
            resizeComposer();
            $('questionInput').focus();
            if (!sessionId) toast('问题已准备好，粘贴 PR 链接或体验演示即可开始。');
        };
    });
    $('newReviewBtn').onclick = () => {
        if (busy) return;
        sessionId = null;
        storage.remove('releaseGuardSessionId');
        storage.remove('releaseGuardRunId');
        clearConversation();
        $('currentReview').hidden = true;
        $('pullRequestInput').value = '';
        $('questionInput').value = '';
        setPrError();
        setRunStatus('待开始');
        setSteps(0);
        resizeComposer();
        $('pullRequestInput').focus();
        toast('新的评审已准备好。');
    };

    async function loadStats() {
        if (statsLoading) return;
        statsLoading = true;
        try {
            const data = await request('/api/stats');
            if (!data || typeof data.totalQueries !== 'number') throw new Error('用量统计暂不可用。');
            const rate = (Number(data.cacheHitRate || 0) * 100).toFixed(1);
            $('queryCount').textContent = number(data.totalQueries);
            $('tokenCount').textContent = number(data.totalTokens);
            $('cacheRate').textContent = rate;
            $('usageQueries').textContent = number(data.totalQueries) + ' 次';
            $('usageTokens').textContent = number(data.totalTokens);
            $('usageHits').textContent = number(data.cacheHits) + ' 次';
            $('usageRate').textContent = rate + '%';
            $('usageDocs').textContent = number(data.documentCount) + ' 份';
            $('documentCount').textContent = number(data.documentCount);
            $('statsDialogNote').textContent = '统计来自当前工作空间的今日问答记录。';
            endpoints.stats = true;
        } catch {
            endpoints.stats = false;
            $('statsDialogNote').textContent = '用量统计暂不可用，请确认后端服务已启动。';
        } finally { statsLoading = false; updateConnection(); }
    }

    function closeSidebar(focus = false) {
        $('sidebar').classList.remove('open');
        $('sidebar').inert = window.matchMedia('(max-width: 760px)').matches;
        $('sidebarBackdrop').hidden = true;
        $('menuBtn').setAttribute('aria-expanded', 'false');
        $('main').inert = false;
        if (focus) $('menuBtn').focus();
    }

    function setChatFocus(focused) {
        if (focused) focusedScrollPosition = window.scrollY;
        document.body.classList.toggle('chat-focused', focused);
        $('focusChatBtn').setAttribute('aria-pressed', String(focused));
        $('focusChatText').textContent = focused ? '退出专注' : '专注对话';
        if (!focused) window.scrollTo({ top: focusedScrollPosition, behavior: 'instant' });
        $('focusChatBtn').focus({ preventScroll: true });
    }

    $('focusChatBtn').onclick = () => setChatFocus(!document.body.classList.contains('chat-focused'));
    $('menuBtn').onclick = () => {
        $('sidebar').inert = false;
        $('sidebar').classList.add('open');
        $('sidebarBackdrop').hidden = false;
        $('menuBtn').setAttribute('aria-expanded', 'true');
        $('main').inert = true;
        $('navWorkspace').focus();
    };
    $('sidebarBackdrop').onclick = () => closeSidebar(true);
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && document.body.classList.contains('chat-focused')
                && !document.querySelector('dialog[open]')) setChatFocus(false);
        if (event.key === 'Escape' && $('sidebar').classList.contains('open')) closeSidebar(true);
        if (event.key === 'Tab' && $('sidebar').classList.contains('open')) {
            const focusable = Array.from($('sidebar').querySelectorAll('a,button')).filter(item => !item.disabled);
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }
    });
    window.matchMedia('(min-width: 761px)').addEventListener('change', () => closeSidebar());
    $('navWorkspace').onclick = () => {
        closeSidebar();
        window.scrollTo({ top: 0, behavior: reducedMotion.matches ? 'instant' : 'smooth' });
        $('pullRequestInput').focus({ preventScroll: true });
    };
    $('navKnowledge').onclick = () => {
        closeSidebar();
        $('knowledgePanel').scrollIntoView({ behavior: reducedMotion.matches ? 'instant' : 'smooth', block: 'center' });
        $('docSearch').focus({ preventScroll: true });
    };
    $('navStats').onclick = () => { closeSidebar(); $('statsDialog').showModal(); void loadStats(); };
    const openGuide = () => { closeSidebar(); $('guideDialog').showModal(); };
    $('guideBtn').onclick = openGuide;
    $('helpBtn').onclick = openGuide;
    document.querySelectorAll('[data-close]').forEach(button => { button.onclick = () => $(button.dataset.close).close(); });
    document.querySelectorAll('dialog').forEach(dialog => dialog.addEventListener('click', event => {
        if (event.target !== dialog) return;
        const box = dialog.getBoundingClientRect();
        if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) dialog.close();
    }));

    $('today').textContent = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long', timeZone: 'Asia/Shanghai' });
    closeSidebar();
    setBusy(true);
    void loadDocs();
    void loadStats();
    initializeSession().finally(() => { initializing = false; if (!activeEventSource) setBusy(false); });
    window.setInterval(() => { if (!document.hidden) void loadDocs(); }, 3000);
    window.setInterval(() => { if (!document.hidden) void loadStats(); }, 60000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) { void loadDocs(); void loadStats(); } });
    window.addEventListener('pagehide', () => { if (activeEventSource) { activeEventSource.close(); activeEventSource = null; } });
    window.addEventListener('pageshow', event => {
        if (event.persisted) {
            const runId = storage.get('releaseGuardRunId');
            if (runId && !activeEventSource) { setBusy(true); subscribeRun(runId); }
            void loadDocs();
            void loadStats();
        }
    });
})();
