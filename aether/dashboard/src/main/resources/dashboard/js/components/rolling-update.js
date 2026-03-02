window.RollingUpdate = {
    phaseColors: {
        'ROUTING': 'var(--accent-blue)',
        'DEPLOYING': 'var(--accent-orange)',
        'VALIDATING': 'var(--accent-purple)',
        'COMPLETE': 'var(--accent-green)',
        'ROLLING_BACK': 'var(--accent-red)'
    },

    getPhaseColor(phase) {
        return this.phaseColors[phase] || 'var(--accent-blue)';
    },

    formatProgress(update) {
        if (!update) return '';
        return update.sliceId + ' ' + (update.fromVersion || '?') + ' \u2192 ' + (update.toVersion || '?') +
               ' [' + (update.phase || 'UNKNOWN') + '] ' + (update.progress || 0) + '%';
    }
};
