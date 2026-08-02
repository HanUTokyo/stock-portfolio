import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MantineProvider } from '@mantine/core';
import RichTextEditor, { richNoteToHtml, richNoteToMarkdown } from './RichTextEditor';

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn()
  }))
});

describe('RichTextEditor', () => {
  it('renders the configured Mantine TipTap controls and parses Markdown content', () => {
    render(
      <MantineProvider>
        <RichTextEditor
          value="# Investment thesis\n\n- [ ] Confirm revenue catalysts"
          onChange={vi.fn()}
          placeholder="Write a note"
          ariaLabel="Research note"
        />
      </MantineProvider>
    );

    expect(screen.getByRole('toolbar', { name: 'Rich text controls' })).not.toBeNull();
    ['Bold', 'Italic', 'Underline', 'Strikethrough', 'Clear formatting', 'Code', 'Heading 1', 'Heading 2', 'Heading 3', 'Bullet list', 'Ordered list', 'Task list', 'Blockquote', 'Code block', 'Horizontal line', 'Link', 'Remove link', 'Undo', 'Redo'].forEach((label) => {
      expect(screen.getByLabelText(label)).not.toBeNull();
    });
    expect(document.querySelector('.ProseMirror')?.textContent).toContain('Investment thesis');
    expect(document.querySelector('.ProseMirror')?.classList.contains('rich-text-surface')).toBe(true);
  });

  it('converts legacy HTML to Markdown and safely renders Markdown previews', () => {
    const legacyMarkdown = richNoteToMarkdown('<h2>Legacy note</h2><p><strong>Keep</strong> this.</p>');
    expect(legacyMarkdown).toContain('Legacy note');
    expect(legacyMarkdown).toContain('**Keep** this.');

    const markup = richNoteToHtml('## New note\n\n- [x] Reviewed\n\n<script>alert(1)</script>');
    expect(markup).toContain('<h2>New note</h2>');
    expect(markup).toContain('type="checkbox"');
    expect(markup).not.toContain('<script');
  });
});
