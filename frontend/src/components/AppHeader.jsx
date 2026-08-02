import {
  ArrowLeftRight,
  BarChart3,
  Languages,
  Moon,
  NotebookText,
  PieChart,
  ReceiptText,
  ShieldCheck,
  Sun,
  Tags,
  WalletCards
} from 'lucide-react';
import { NavLink } from 'react-router-dom';
import { useState } from 'react';
import LogoMark from './LogoMark';

const navItems = [
  { to: '/overview', label: 'Overview', icon: PieChart },
  { to: '/market', label: 'Market Data', icon: BarChart3 },
  { to: '/notes', label: 'Notes', icon: NotebookText },
  { to: '/transactions', label: 'Transactions', icon: ArrowLeftRight },
  { to: '/cash', label: 'Cash', icon: WalletCards },
  { to: '/dividends', label: 'Dividends', icon: ReceiptText },
  { to: '/classifications', label: 'Classifications', icon: Tags },
  { to: '/admin/data-review', label: 'Data Review', icon: ShieldCheck }
];

export default function AppHeader({
  brand,
  languageLabel,
  languages,
  currentLanguage,
  theme,
  onLanguageChange,
  onThemeToggle
}) {
  const [languageMenuOpen, setLanguageMenuOpen] = useState(false);

  return (
    <header className="app-header">
      <div className="app-header-main">
        <div className="brand-lockup">
          <LogoMark />
          <div className="brand-copy">
            <h1 className="brand" data-i18n-managed="true">
              {brand}
            </h1>
          </div>
        </div>

        <div className="header-actions" aria-label="Application tools">
          <div className="language-switcher">
            <button
              type="button"
              className="icon-button language-trigger"
              onClick={() => setLanguageMenuOpen((open) => !open)}
              title={languageLabel}
              aria-label={languageLabel}
              aria-expanded={languageMenuOpen}
              aria-haspopup="menu"
            >
              <Languages size={18} aria-hidden="true" />
            </button>
            {languageMenuOpen && (
              <div className="language-menu" role="menu" aria-label={languageLabel}>
                {languages.map((language) => (
                  <button
                    key={language.code}
                    type="button"
                    role="menuitem"
                    className={language.code === currentLanguage ? 'is-active' : ''}
                    onClick={() => {
                      onLanguageChange(language.code);
                      setLanguageMenuOpen(false);
                    }}
                  >
                    {language.label}
                  </button>
                ))}
              </div>
            )}
          </div>
          <button
            type="button"
            className="icon-button"
            onClick={onThemeToggle}
            title={theme === 'dark' ? 'Switch to Light' : 'Switch to Dark'}
            aria-label={theme === 'dark' ? 'Switch to Light' : 'Switch to Dark'}
          >
            {theme === 'dark' ? <Sun size={18} aria-hidden="true" /> : <Moon size={18} aria-hidden="true" />}
          </button>
        </div>
      </div>

      <nav className="tabs" aria-label="Portfolio sections">
        {navItems.map(({ to, label, icon: Icon }) => (
          <NavLink key={to} to={to} className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>
            <Icon size={16} aria-hidden="true" />
            <span className="tab-label">{label}</span>
          </NavLink>
        ))}
      </nav>
    </header>
  );
}
