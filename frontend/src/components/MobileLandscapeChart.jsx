import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Maximize2, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import useBodyScrollLock from '../hooks/useBodyScrollLock';
import i18n from '../i18n';

function getFullscreenElement() {
  return document.fullscreenElement || document.webkitFullscreenElement || null;
}

function exitFullscreen() {
  if (document.exitFullscreen) return document.exitFullscreen();
  if (document.webkitExitFullscreen) return document.webkitExitFullscreen();
  return undefined;
}

function shouldUseCssOnlyFullscreen() {
  if (typeof window === 'undefined') return false;
  const screenWidth = window.screen?.width || window.innerWidth;
  const screenHeight = window.screen?.height || window.innerHeight;
  const shortestSide = Math.min(screenWidth, screenHeight);
  const hasTouch = navigator.maxTouchPoints > 0 || window.matchMedia?.('(pointer: coarse)').matches;
  return hasTouch && shortestSide >= 600;
}

export default function MobileLandscapeChart({ title, children, className = '' }) {
  const { t } = useTranslation();
  const titleId = useId();
  const triggerRef = useRef(null);
  const closeRef = useRef(null);
  const openRef = useRef(false);
  const wasOpenRef = useRef(false);
  const ownsNativeFullscreenRef = useRef(false);
  const orientationLockedRef = useRef(false);
  const [open, setOpen] = useState(false);
  const [fullscreenMode, setFullscreenMode] = useState('css');

  useBodyScrollLock(open, 'pan-x pan-y');

  const unlockOrientation = useCallback(() => {
    if (!orientationLockedRef.current) return;
    try {
      window.screen?.orientation?.unlock?.();
    } catch {
      // Orientation locking is best-effort and unavailable in some mobile browsers.
    }
    orientationLockedRef.current = false;
  }, []);

  const close = useCallback(() => {
    openRef.current = false;
    setOpen(false);
    unlockOrientation();

    if (ownsNativeFullscreenRef.current && getFullscreenElement()) {
      try {
        const result = exitFullscreen();
        if (result?.catch) result.catch(() => {});
      } catch {
        // The CSS landscape fallback remains closable even when native exit fails.
      }
    }
    ownsNativeFullscreenRef.current = false;
  }, [unlockOrientation]);

  const lockLandscape = useCallback(async () => {
    if (!window.screen?.orientation?.lock) return;
    try {
      await window.screen.orientation.lock('landscape');
      if (openRef.current) {
        orientationLockedRef.current = true;
      } else {
        window.screen.orientation.unlock?.();
      }
    } catch {
      // iOS Safari and many embedded browsers reject orientation locking.
    }
  }, []);

  const openLandscape = useCallback(() => {
    openRef.current = true;
    setOpen(true);

    // iPad Safari adds its own native fullscreen layer and close control. When
    // combined with our rotated landscape canvas it can double-rotate the
    // stage and expose the page underneath, so tablets use the CSS canvas.
    if (shouldUseCssOnlyFullscreen()) {
      setFullscreenMode('css');
      return;
    }

    setFullscreenMode('native-pending');

    if (getFullscreenElement()) return;

    const root = document.documentElement;
    const request = root.requestFullscreen || root.webkitRequestFullscreen;
    if (!request) return;

    try {
      const result = request.call(root, { navigationUI: 'hide' });
      Promise.resolve(result)
        .then(() => {
          if (!openRef.current) {
            if (getFullscreenElement()) {
              const exitResult = exitFullscreen();
              if (exitResult?.catch) exitResult.catch(() => {});
            }
            return;
          }
          ownsNativeFullscreenRef.current = true;
          setFullscreenMode('native');
          lockLandscape();
        })
        .catch(() => {
          ownsNativeFullscreenRef.current = false;
          setFullscreenMode('css');
        });
    } catch {
      ownsNativeFullscreenRef.current = false;
      setFullscreenMode('css');
    }
  }, [lockLandscape]);

  useEffect(() => {
    if (open) {
      wasOpenRef.current = true;
      const frame = window.requestAnimationFrame(() => closeRef.current?.focus());
      return () => window.cancelAnimationFrame(frame);
    }

    if (wasOpenRef.current) {
      wasOpenRef.current = false;
      const frame = window.requestAnimationFrame(() => triggerRef.current?.focus());
      return () => window.cancelAnimationFrame(frame);
    }
    return undefined;
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event) => {
      if (event.key === 'Escape') close();
    };
    const onFullscreenChange = () => {
      if (ownsNativeFullscreenRef.current && !getFullscreenElement()) {
        ownsNativeFullscreenRef.current = false;
        unlockOrientation();
        openRef.current = false;
        setOpen(false);
      }
    };

    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('fullscreenchange', onFullscreenChange);
    document.addEventListener('webkitfullscreenchange', onFullscreenChange);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('fullscreenchange', onFullscreenChange);
      document.removeEventListener('webkitfullscreenchange', onFullscreenChange);
    };
  }, [close, open, unlockOrientation]);

  useEffect(() => () => {
    openRef.current = false;
    unlockOrientation();
    if (ownsNativeFullscreenRef.current && getFullscreenElement()) {
      try {
        const result = exitFullscreen();
        if (result?.catch) result.catch(() => {});
      } catch {
        // Nothing else to clean up when the browser controls fullscreen ownership.
      }
    }
    ownsNativeFullscreenRef.current = false;
  }, [unlockOrientation]);

  const translatedTitle = i18n.t(`auto.${title}`, { defaultValue: title });
  const openLabel = t('ui.openChartFullscreen', { title: translatedTitle });
  const closeLabel = t('ui.closeChartFullscreen', { title: translatedTitle });
  const portalTarget = typeof document === 'undefined' ? null : (getFullscreenElement() || document.body);

  return (
    <div className={`mobile-landscape-chart ${className}`.trim()} data-chart-title={translatedTitle}>
      <div className="mobile-landscape-chart-inline">
        <button
          ref={triggerRef}
          type="button"
          className="mobile-landscape-chart-trigger"
          data-i18n-managed="true"
          aria-label={openLabel}
          title={openLabel}
          onClick={openLandscape}
        >
          <Maximize2 size={18} aria-hidden="true" />
        </button>
        {children}
      </div>

      {open && portalTarget ? createPortal(
        <div
          className="mobile-chart-fullscreen"
          data-fullscreen-mode={fullscreenMode}
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
        >
          <section className="mobile-chart-fullscreen-stage">
            <header className="mobile-chart-fullscreen-header">
              <h2 id={titleId} data-i18n-managed="true">{translatedTitle}</h2>
              <button
                ref={closeRef}
                type="button"
                className="mobile-chart-fullscreen-close"
                data-i18n-managed="true"
                aria-label={closeLabel}
                title={closeLabel}
                onClick={close}
              >
                <X size={22} aria-hidden="true" />
              </button>
            </header>
            <div className="mobile-chart-fullscreen-content">{children}</div>
          </section>
        </div>,
        portalTarget
      ) : null}
    </div>
  );
}
