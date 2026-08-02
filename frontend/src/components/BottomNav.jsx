import {
  ArrowLeftRight,
  BarChart3,
  CircleEllipsis,
  NotebookText,
  PieChart,
  ReceiptText,
  Tags,
  WalletCards
} from 'lucide-react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useState } from 'react';
import BottomSheet from './BottomSheet';

const primaryItems = [
  { to: '/overview', label: 'Overview', icon: PieChart },
  { to: '/market', label: 'Market', icon: BarChart3 },
  { to: '/notes', label: 'Notes', icon: NotebookText },
  { to: '/transactions', label: 'Trades', icon: ArrowLeftRight }
];

const moreItems = [
  { to: '/cash', label: 'Cash', icon: WalletCards },
  { to: '/dividends', label: 'Dividends', icon: ReceiptText },
  { to: '/classifications', label: 'Classifications', icon: Tags }
];

export default function BottomNav() {
  const [moreOpen, setMoreOpen] = useState(false);
  const navigate = useNavigate();

  function go(to) {
    setMoreOpen(false);
    navigate(to);
  }

  return (
    <>
      <nav className="bottom-nav" aria-label="Mobile portfolio navigation">
        {primaryItems.map(({ to, label, icon: Icon }) => (
          <NavLink key={to} to={to} className={({ isActive }) => (isActive ? 'bottom-nav-item active' : 'bottom-nav-item')}>
            <Icon size={18} aria-hidden="true" />
            <span>{label}</span>
          </NavLink>
        ))}
        <button type="button" className="bottom-nav-item" onClick={() => setMoreOpen(true)}>
          <CircleEllipsis size={18} aria-hidden="true" />
          <span>More</span>
        </button>
      </nav>

      <BottomSheet open={moreOpen} title="More Sections" onClose={() => setMoreOpen(false)}>
        <div className="more-nav-list">
          {moreItems.map(({ to, label, icon: Icon }) => (
            <button key={to} type="button" className="more-nav-item" onClick={() => go(to)}>
              <Icon size={18} aria-hidden="true" />
              <span>{label}</span>
            </button>
          ))}
        </div>
      </BottomSheet>
    </>
  );
}
