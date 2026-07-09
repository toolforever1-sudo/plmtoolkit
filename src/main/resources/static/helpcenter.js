// === Help Center — Document Knowledge Base ===

var _hcLoaded = false;

function loadHelpCenter() {
    if (_hcLoaded) return;
    _hcLoaded = true;
    refreshHelpCenterDocs();

    // Drag and drop
    var dz = document.getElementById('hcDropZone');
    if (dz) {
        dz.addEventListener('dragover', function(e) { e.preventDefault(); dz.style.borderColor = '#4a6fa5'; dz.style.background = '#f0f4ff'; });
        dz.addEventListener('dragleave', function() { dz.style.borderColor = '#d0d5dd'; dz.style.background = ''; });
        dz.addEventListener('drop', function(e) {
            e.preventDefault(); dz.style.borderColor = '#d0d5dd'; dz.style.background = '';
            if (e.dataTransfer.files.length > 0) uploadHelpDocs(e.dataTransfer.files);
        });
    }
}

function refreshHelpCenterDocs() {
    fetch('/api/help-docs/list')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            document.getElementById('hcDocCount').textContent = data.docCount || 0;
            document.getElementById('hcChunkCount').textContent = data.chunkCount || 0;

            var docs = data.docs || [];
            var container = document.getElementById('helpCenterDocList');
            if (docs.length === 0) {
                container.innerHTML = '<div style="text-align:center; color:#aaa; padding:30px;">No documents uploaded yet. Upload PPTX, PDF, or XLSX files to build your knowledge base.</div>';
                return;
            }

            var html = '';
            docs.forEach(function(doc) {
                var ext = doc.filename.split('.').pop().toUpperCase();
                var extColor = ext === 'PDF' ? '#dc3545' : ext === 'PPTX' ? '#e67e22' : '#28a745';
                var size = doc.fileSize > 1048576 ? (doc.fileSize / 1048576).toFixed(1) + ' MB' : Math.round(doc.fileSize / 1024) + ' KB';
                var date = doc.uploadedAt ? doc.uploadedAt.substring(0, 10) : '';

                html += '<div style="display:flex; align-items:center; gap:12px; padding:12px 14px; border:1px solid #e8e6df; border-radius:6px; margin-bottom:8px; background:#fff;">';
                // File type badge
                html += '<span style="background:' + extColor + '; color:#fff; padding:3px 8px; border-radius:4px; font-size:10px; font-weight:700; letter-spacing:.5px; flex-shrink:0;">' + ext + '</span>';
                // Title + filename
                html += '<div style="flex:1; min-width:0;">';
                html += '<div style="font-weight:600; font-size:13px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">' + esc(doc.title) + '</div>';
                html += '<div style="font-size:11px; color:#999;">' + esc(doc.filename) + ' \u00b7 ' + doc.chunkCount + ' chunks \u00b7 ' + size + '</div>';
                html += '</div>';
                // Meta
                html += '<div style="text-align:right; flex-shrink:0; font-size:11px; color:#888;">';
                html += '<div>' + esc(doc.uploadedBy || '') + '</div>';
                html += '<div>' + date + '</div>';
                html += '</div>';
                // Actions
                html += '<div style="display:flex; gap:6px; flex-shrink:0;">';
                html += '<a href="/api/help-docs/' + doc.id + '/download" style="padding:5px 10px; border:1px solid #d0d5dd; border-radius:5px; font-size:11px; color:#333; text-decoration:none; background:#f8f9fa;">\u2913</a>';
                html += '<button onclick="deleteHelpDoc(\'' + doc.id + '\')" style="padding:5px 10px; border:1px solid #d0d5dd; border-radius:5px; font-size:11px; color:#dc3545; background:#fff; cursor:pointer;">\u2717</button>';
                html += '</div>';
                html += '</div>';
            });
            container.innerHTML = html;
        })
        .catch(function() {
            document.getElementById('helpCenterDocList').innerHTML = '<div style="color:#dc3545; padding:20px;">Failed to load documents.</div>';
        });
}

function uploadHelpDocs(files) {
    var status = document.getElementById('hcUploadStatus');
    var total = files.length;
    var done = 0;
    status.textContent = 'Uploading ' + total + ' file(s)...';
    status.style.color = '#856404';

    Array.from(files).forEach(function(file) {
        var formData = new FormData();
        formData.append('file', file);

        fetch('/api/help-docs/upload', { method: 'POST', body: formData })
            .then(function(r) { return r.json(); })
            .then(function(data) {
                done++;
                if (data.ok) {
                    status.textContent = done + '/' + total + ' uploaded — ' + file.name + ' (' + data.doc.chunkCount + ' chunks)';
                } else {
                    status.textContent = done + '/' + total + ' — ' + file.name + ': ' + (data.error || 'failed');
                    status.style.color = '#dc3545';
                }
                if (done === total) {
                    status.style.color = '#28a745';
                    status.textContent = '\u2713 All ' + total + ' file(s) uploaded and indexed.';
                    _hcLoaded = false;
                    loadHelpCenter();
                }
            })
            .catch(function() {
                done++;
                status.textContent = done + '/' + total + ' — ' + file.name + ': upload failed';
                status.style.color = '#dc3545';
            });
    });
}

function deleteHelpDoc(id) {
    appConfirm('Delete this document from the knowledge base?', { danger: true, okText: 'Delete' }).then(function (ok) {
        if (!ok) return;
        fetch('/api/help-docs/' + id, { method: 'DELETE' })
            .then(function() {
                _hcLoaded = false;
                loadHelpCenter();
            });
    });
}

function searchHelpCenter() {
    var q = document.getElementById('helpCenterSearch').value.trim();
    if (!q) return;

    var results = document.getElementById('helpCenterResults');
    results.style.display = '';
    results.innerHTML = '<div style="color:#888;">Searching...</div>';

    fetch('/api/help-docs/search?q=' + encodeURIComponent(q) + '&max=10')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data || data.length === 0) {
                results.innerHTML = '<div style="padding:16px; background:#fff8e1; border:1px solid #e6a817; border-radius:6px; color:#856404;">No results found for "' + esc(q) + '". Try different keywords.</div>';
                return;
            }

            var html = '<div style="font-size:13px; font-weight:600; color:#1a3a5c; margin-bottom:8px;">' + data.length + ' result(s) for "' + esc(q) + '"</div>';
            data.forEach(function(r) {
                var text = r.text;
                if (text.length > 400) text = text.substring(0, 397) + '...';

                // Highlight matched terms
                var highlighted = esc(text);
                if (r.matchedTerms) {
                    r.matchedTerms.forEach(function(term) {
                        var regex = new RegExp('(' + term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'gi');
                        highlighted = highlighted.replace(regex, '<mark style="background:#fff3cd; padding:0 2px;">$1</mark>');
                    });
                }

                html += '<div style="background:#fff; border:1px solid #e8e6df; border-left:3px solid #4a6fa5; border-radius:0 6px 6px 0; padding:12px 16px; margin-bottom:8px;">';
                html += '<div style="display:flex; justify-content:space-between; margin-bottom:6px;">';
                html += '<span style="font-size:12px; font-weight:600; color:#1a3a5c;">' + esc(r.docTitle) + '</span>';
                html += '<span style="font-size:11px; color:#888;">Slide/Page ' + r.slideOrPage + ' \u00b7 Score: ' + r.score + '</span>';
                html += '</div>';
                html += '<div style="font-size:13px; line-height:1.6; color:#333;">' + highlighted + '</div>';
                html += '</div>';
            });

            html += '<p style="font-size:11px; color:#28a745;">\u26A1 Keyword search \u2014 no AI needed</p>';
            results.innerHTML = html;
        })
        .catch(function() {
            results.innerHTML = '<div style="color:#dc3545;">Search failed.</div>';
        });
}

function esc(s) {
    if (!s) return '';
    var div = document.createElement('div');
    div.textContent = s;
    return div.innerHTML;
}
