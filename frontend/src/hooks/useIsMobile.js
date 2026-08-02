import { useEffect, useState } from 'react';

export default function useIsMobile(breakpoint = 820) {
  const [isMobile, setIsMobile] = useState(() => (
    typeof window !== 'undefined' ? window.matchMedia(`(max-width: ${breakpoint}px)`).matches : false
  ));

  useEffect(() => {
    const query = window.matchMedia(`(max-width: ${breakpoint}px)`);
    const handleChange = () => setIsMobile(query.matches);
    handleChange();
    query.addEventListener('change', handleChange);
    return () => query.removeEventListener('change', handleChange);
  }, [breakpoint]);

  return isMobile;
}
