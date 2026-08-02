import { useEffect } from 'react';

function labelTables(root = document) {
  root.querySelectorAll('table').forEach((table) => {
    const headers = [...table.querySelectorAll('thead th')].map((header) => (
      header.textContent
        .replace(/[▲▼]/g, '')
        .replace(/\s+/g, ' ')
        .trim()
    ));

    if (!headers.length) {
      return;
    }

    table.classList.add('responsive-card-table');

    table.querySelectorAll('tbody tr, tfoot tr').forEach((row) => {
      [...row.children].forEach((cell, index) => {
        if (cell.tagName !== 'TD') {
          return;
        }
        const label = headers[index] || '';
        if (label) {
          cell.setAttribute('data-label', label);
        }
      });
    });
  });
}

export default function useResponsiveTables(dependencies = []) {
  useEffect(() => {
    const applyLabels = () => labelTables();
    const frame = window.requestAnimationFrame(applyLabels);
    const observer = new MutationObserver(() => {
      window.requestAnimationFrame(applyLabels);
    });

    observer.observe(document.body, {
      childList: true,
      subtree: true
    });

    return () => {
      window.cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, dependencies);
}
