import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';

const textOriginals = new WeakMap();
const attributeOriginals = new WeakMap();
const translatedAttributes = ['placeholder', 'title', 'aria-label'];
const skipTextParents = new Set(['SCRIPT', 'STYLE', 'TEXTAREA']);

function withOriginalSpacing(original, translated) {
  const leading = original.match(/^\s*/)?.[0] || '';
  const trailing = original.match(/\s*$/)?.[0] || '';
  return `${leading}${translated}${trailing}`;
}

function translateDirect(text, t) {
  const trimmed = text.replace(/\s+/g, ' ').trim();
  if (!trimmed) return text;
  const translated = t(`auto.${trimmed}`, { defaultValue: trimmed });
  return withOriginalSpacing(text, translated);
}

function translatePattern(text, t) {
  const trimmed = text.replace(/\s+/g, ' ').trim();
  const currentHoldings = trimmed.match(/^Current Holdings \((\d+)\)$/);
  if (currentHoldings) return withOriginalSpacing(text, `${t('auto.Current Holdings', { defaultValue: 'Current Holdings' })} (${currentHoldings[1]})`);

  const pastHoldings = trimmed.match(/^Past Holdings \((\d+)\)$/);
  if (pastHoldings) return withOriginalSpacing(text, `${t('auto.Past Holdings', { defaultValue: 'Past Holdings' })} (${pastHoldings[1]})`);

  const allCount = trimmed.match(/^All \((\d+)\)$/);
  if (allCount) return withOriginalSpacing(text, `${t('auto.All', { defaultValue: 'All' })} (${allCount[1]})`);

  const withNotesCount = trimmed.match(/^With Notes \((\d+)\)$/);
  if (withNotesCount) return withOriginalSpacing(text, `${t('auto.With Notes', { defaultValue: 'With Notes' })} (${withNotesCount[1]})`);

  const totalRecords = trimmed.match(/^(\d+) total records$/);
  if (totalRecords) {
    const noun = t('auto.total records', { defaultValue: 'total records' });
    if (noun !== 'total records') return withOriginalSpacing(text, `${totalRecords[1]} ${noun}`);
  }

  const page = trimmed.match(/^Page (\d+) of (\d+)$/);
  if (page) {
    const localized = t('auto.Page of', { defaultValue: `Page ${page[1]} of ${page[2]}`, current: page[1], total: page[2] });
    if (localized !== `Page ${page[1]} of ${page[2]}`) return withOriginalSpacing(text, localized);
  }

  const noSavedStockNote = trimmed.match(/^No saved stock note for (.+) yet\.$/);
  if (noSavedStockNote) {
    const localized = t('auto.No saved stock note for symbol yet.', {
      defaultValue: `No saved stock note for ${noSavedStockNote[1]} yet.`,
      symbol: noSavedStockNote[1]
    });
    return withOriginalSpacing(text, localized);
  }

  const symbolFlexibleBuy = trimmed.match(/^(.+) Flexible Buy Reminder$/);
  if (symbolFlexibleBuy) {
    const label = t('auto.Flexible Buy Reminder', { defaultValue: 'Flexible Buy Reminder' });
    return withOriginalSpacing(text, `${symbolFlexibleBuy[1]} ${label}`);
  }

  const lowerFlexibleBuy = trimmed.match(/^(.+) flexible buy reminder$/);
  if (lowerFlexibleBuy) {
    const label = t('auto.flexible buy reminder', { defaultValue: 'flexible buy reminder' });
    return withOriginalSpacing(text, `${lowerFlexibleBuy[1]} ${label}`);
  }

  const stockMv = trimmed.match(/^Stock MV (.+)$/);
  if (stockMv) {
    const label = t('auto.Stock MV', { defaultValue: 'Stock MV' });
    return withOriginalSpacing(text, `${label} ${stockMv[1]}`);
  }

  const costBasis = trimmed.match(/^Cost Basis (.+)$/);
  if (costBasis) {
    const label = t('auto.Cost Basis', { defaultValue: 'Cost Basis' });
    return withOriginalSpacing(text, `${label} ${costBasis[1]}`);
  }

  const totalAssets = trimmed.match(/^Total Assets (.+)$/);
  if (totalAssets) {
    const label = t('auto.Total Assets', { defaultValue: 'Total Assets' });
    return withOriginalSpacing(text, `${label} ${totalAssets[1]}`);
  }

  const currentCashBalance = trimmed.match(/^Current Cash Balance: (.+)$/);
  if (currentCashBalance) {
    const label = t('auto.Current Cash Balance:', { defaultValue: 'Current Cash Balance:' });
    return withOriginalSpacing(text, `${label} ${currentCashBalance[1]}`);
  }

  const portfolioValue = trimmed.match(/^Portfolio (\$.*)$/);
  if (portfolioValue) {
    const label = t('auto.Portfolio', { defaultValue: 'Portfolio' });
    return withOriginalSpacing(text, `${label} ${portfolioValue[1]}`);
  }

  const costValue = trimmed.match(/^Cost (\$.*)$/);
  if (costValue) {
    const label = t('auto.Cost', { defaultValue: 'Cost' });
    return withOriginalSpacing(text, `${label} ${costValue[1]}`);
  }

  return null;
}

function translateValue(value, t) {
  const direct = translateDirect(value, t);
  if (direct !== value) return direct;
  const pattern = translatePattern(value, t);
  if (pattern != null) return pattern;
  return direct;
}

function translateTextNode(node, t) {
  if (!node.parentElement || skipTextParents.has(node.parentElement.tagName)) return;
  if (node.parentElement.closest('[data-i18n-managed="true"]')) return;
  if (!textOriginals.has(node)) {
    textOriginals.set(node, node.textContent || '');
  }
  const original = textOriginals.get(node);
  if (!original || !original.trim()) return;
  const translated = translateValue(original, t);
  if (node.textContent !== translated) {
    node.textContent = translated;
  }
}

function translateElementAttributes(element, t) {
  if (element.closest('[data-i18n-managed="true"]')) return;
  translatedAttributes.forEach((attributeName) => {
    if (!element.hasAttribute(attributeName)) return;
    let originals = attributeOriginals.get(element);
    if (!originals) {
      originals = {};
      attributeOriginals.set(element, originals);
    }
    if (originals[attributeName] == null) {
      originals[attributeName] = element.getAttribute(attributeName) || '';
    }
    const original = originals[attributeName];
    if (!original.trim()) return;
    const translated = translateValue(original, t);
    if (element.getAttribute(attributeName) !== translated) {
      element.setAttribute(attributeName, translated);
    }
  });
}

function translateTree(root, t) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
  let node = walker.currentNode;
  while (node) {
    if (node.nodeType === Node.TEXT_NODE) {
      translateTextNode(node, t);
    } else if (node.nodeType === Node.ELEMENT_NODE) {
      translateElementAttributes(node, t);
    }
    node = walker.nextNode();
  }
}

export function useAutoTranslate() {
  const { t, i18n } = useTranslation();
  const rootRef = useRef(null);

  useEffect(() => {
    const root = rootRef.current;
    if (!root) return undefined;

    // Translate the first static render only. Observing every DOM mutation used to
    // overwrite React's live values (prices, KPIs, and table cells) with stale text.
    translateTree(root, t);

    document.documentElement.lang = i18n.language;
    window.localStorage.setItem('portfolio-language', i18n.language);

    return undefined;
  }, [t, i18n.language]);

  return rootRef;
}
