import { useEffect } from 'react';

export default function useBodyScrollLock(locked, touchAction = 'none') {
  useEffect(() => {
    if (!locked) {
      return undefined;
    }
    const previousOverflow = document.body.style.overflow;
    const previousTouchAction = document.body.style.touchAction;
    document.body.style.overflow = 'hidden';
    document.body.style.touchAction = touchAction;
    return () => {
      document.body.style.overflow = previousOverflow;
      document.body.style.touchAction = previousTouchAction;
    };
  }, [locked, touchAction]);
}
