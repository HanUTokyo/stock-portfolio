import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

function normalizeStatus(status) {
  return String(status || 'neutral').toLowerCase().replace(/\s+/g, '-');
}

function isRedundantUnit(value, unit) {
  const text = String(value ?? '').trim();
  if (!unit || text === 'N/A') return true;
  if (unit === '%') return text.endsWith('%');
  if (unit === 'pp') return /\bpp$/.test(text);
  if (unit === 'multiple') return text.endsWith('x');
  return false;
}

export default function FundamentalMetricCard({ title, value, unit, status, tooltip }) {
  const [tooltipOpen, setTooltipOpen] = useState(false);
  const cardRef = useRef(null);
  const tooltipId = useId();
  const statusClass = normalizeStatus(status);
  const redundantUnit = isRedundantUnit(value, unit);

  useEffect(() => {
    if (!tooltipOpen) return undefined;

    function closeOnOutsidePointer(event) {
      if (!cardRef.current?.contains(event.target)) setTooltipOpen(false);
    }

    function closeOnEscape(event) {
      if (event.key === 'Escape') setTooltipOpen(false);
    }

    document.addEventListener('pointerdown', closeOnOutsidePointer);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOnOutsidePointer);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [tooltipOpen]);

  return (
    <>
      <article ref={cardRef} className="gross-margin-summary-card" title={tooltip}>
        <span className="metric-card-title">
          <span>{title}</span>
          {tooltip ? (
            <button
              type="button"
              className={`metric-help${tooltipOpen ? ' is-open' : ''}`}
              aria-label={`${title} calculation method`}
              aria-expanded={tooltipOpen}
              aria-describedby={tooltipOpen ? tooltipId : undefined}
              onClick={() => setTooltipOpen((open) => !open)}
            >
              ?
              <span className="metric-help-bubble" role="tooltip">{tooltip}</span>
            </button>
          ) : null}
        </span>
        <span className="metric-card-value">
          <strong>{value}</strong>
          {!redundantUnit ? (
            <small className={unit === '$' ? 'metric-card-unit metric-card-unit-prefix' : 'metric-card-unit'}>{unit}</small>
          ) : null}
        </span>
        {status ? <em className={`gross-margin-status gross-margin-status-${statusClass}`}>{status}</em> : null}
      </article>
      {tooltipOpen && tooltip ? createPortal(
        <span id={tooltipId} className="metric-help-mobile-popover" role="tooltip">{tooltip}</span>,
        document.body
      ) : null}
    </>
  );
}
