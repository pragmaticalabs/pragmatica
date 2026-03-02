window.RestClient = {
    get: function(path) {
        return fetch(path).then(function(response) {
            if (!response.ok) return null;
            return response.json();
        }).catch(function(e) {
            console.warn('REST GET failed:', path, e.message);
            return null;
        });
    },

    post: function(path, body) {
        var opts = { method: 'POST', headers: {} };
        if (body != null) {
            if (typeof body === 'string') {
                opts.headers['Content-Type'] = 'text/plain';
                opts.body = body;
            } else {
                opts.headers['Content-Type'] = 'application/json';
                opts.body = JSON.stringify(body);
            }
        }
        return fetch(path, opts).then(function(response) {
            if (!response.ok) return null;
            return response.text().then(function(text) {
                try { return JSON.parse(text); } catch(e) { return text; }
            });
        }).catch(function(e) {
            console.warn('REST POST failed:', path, e.message);
            return null;
        });
    },

    put: function(path, body) {
        var opts = {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        };
        return fetch(path, opts).then(function(response) {
            return response.ok;
        }).catch(function(e) {
            console.warn('REST PUT failed:', path, e.message);
            return false;
        });
    },

    del: function(path) {
        return fetch(path, { method: 'DELETE' }).then(function(response) {
            return response.ok;
        }).catch(function(e) {
            console.warn('REST DELETE failed:', path, e.message);
            return false;
        });
    }
};
