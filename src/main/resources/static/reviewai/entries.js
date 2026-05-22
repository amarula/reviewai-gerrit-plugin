(function(global) {
  const reviewAi = global.ReviewAi;

  reviewAi.entries = {
    fromResult(result) {
      return Array.isArray(result && result.entries) ? result.entries : [];
    },

    formatLocation(entry) {
      if (entry.filename) {
        return entry.line ? `${entry.filename}:${entry.line}` : entry.filename;
      }
      if (entry.patchSet) {
        return `Patch Set ${entry.patchSet}`;
      }
      return 'Change thread';
    },

    formatReviewScore(entry) {
      const score =
        entry && entry.reviewScore !== undefined ? entry.reviewScore : entry && entry.review_score;
      if (score === null || score === undefined || String(score).trim() === '') {
        return '';
      }
      return `Code-Review ${String(score).trim()}`;
    },

    formatLocationWithReviewScore(entry) {
      const location = this.formatLocation(entry);
      const score = this.formatReviewScore(entry);
      if (!score) {
        return location;
      }
      return location && location !== 'Change thread' ? `${location} - ${score}` : score;
    },

    parseTimestamp(value) {
      if (!value) {
        return null;
      }

      const match = value.match(
        /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?$/
      );
      if (!match) {
        return null;
      }

      const milliseconds = Number((match[7] || '').slice(0, 3).padEnd(3, '0'));
      const timestamp = Date.UTC(
        Number(match[1]),
        Number(match[2]) - 1,
        Number(match[3]),
        Number(match[4]),
        Number(match[5]),
        Number(match[6]),
        milliseconds
      );

      return Number.isNaN(timestamp) ? null : new Date(timestamp);
    },

  };
})(window);
