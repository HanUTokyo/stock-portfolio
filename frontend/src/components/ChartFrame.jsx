import { useEffect, useRef, useState } from 'react';

export default function ChartFrame({
  children,
  className = '',
  caption,
  legend,
  compactAt = 640,
  scrollable = false,
  scrollKey
}) {
  const ref = useRef(null);
  const viewportRef = useRef(null);
  const [width, setWidth] = useState(0);

  useEffect(() => {
    if (!ref.current || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.observe(ref.current);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!scrollable || !viewportRef.current) return undefined;
    const frame = window.requestAnimationFrame(() => {
      const viewport = viewportRef.current;
      if (viewport) viewport.scrollLeft = viewport.scrollWidth;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [scrollable, scrollKey]);

  const compact = width > 0 && width < compactAt;

  return (
    <div ref={ref} className={`chart-frame ${compact ? 'chart-frame-compact' : ''} ${scrollable ? 'is-scrollable' : ''} ${className}`.trim()}>
      <div ref={viewportRef} className="chart-frame-viewport">
        {typeof children === 'function' ? children({ width, compact }) : children}
      </div>
      {legend ? <div className="chart-frame-legend">{legend}</div> : null}
      {caption ? <p className="chart-frame-caption">{caption}</p> : null}
    </div>
  );
}
