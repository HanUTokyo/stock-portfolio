import { useEffect, useMemo, useRef } from 'react';
import { RichTextEditor as MantineRichTextEditor } from '@mantine/tiptap';
import { useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Link from '@tiptap/extension-link';
import Placeholder from '@tiptap/extension-placeholder';
import TaskItem from '@tiptap/extension-task-item';
import TaskList from '@tiptap/extension-task-list';
import Underline from '@tiptap/extension-underline';
import { Markdown } from '@tiptap/markdown';
import { marked } from 'marked';
import TurndownService from 'turndown';
import {
  Pencil,
  RotateCcw,
  Save
} from 'lucide-react';

const richNoteTags = new Set(['P', 'BR', 'STRONG', 'B', 'EM', 'I', 'U', 'S', 'STRIKE', 'DEL', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BLOCKQUOTE', 'UL', 'OL', 'LI', 'A', 'CODE', 'PRE', 'HR', 'INPUT']);
const blockedRichNoteTags = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED']);
const turndownService = new TurndownService({ bulletListMarker: '-', codeBlockStyle: 'fenced' });

function isHtmlRichNote(value) {
  const tagNames = [...richNoteTags].map((tag) => tag.toLowerCase()).join('|');
  return new RegExp(`<\\/?(?:${tagNames})(?:\\s|\\/?>)`, 'i').test(String(value || ''));
}

function escapeRichNoteText(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function plainTextToRichNote(value) {
  const paragraphs = String(value || '').split(/\n{2,}/).filter((paragraph) => paragraph.trim());
  return paragraphs.map((paragraph) => `<p>${escapeRichNoteText(paragraph).replace(/\n/g, '<br>')}</p>`).join('');
}

export function sanitizeRichNote(value, { allowHeadings = true } = {}) {
  const raw = String(value || '');
  if (!raw) return '';
  if (typeof window === 'undefined' || typeof DOMParser === 'undefined') return plainTextToRichNote(raw);

  const documentFragment = new DOMParser().parseFromString(raw, 'text/html');
  const hasSupportedMarkup = [...documentFragment.body.children].some((node) => richNoteTags.has(node.tagName));
  if (!hasSupportedMarkup) return plainTextToRichNote(raw);

  function sanitizeNode(parent) {
    [...parent.childNodes].forEach((node) => {
      if (node.nodeType !== Node.ELEMENT_NODE) return;
      const element = node;
      if (blockedRichNoteTags.has(element.tagName)) {
        element.remove();
        return;
      }
      if (!allowHeadings && /^H[1-6]$/.test(element.tagName)) {
        const paragraph = documentFragment.createElement('p');
        while (element.firstChild) paragraph.appendChild(element.firstChild);
        element.replaceWith(paragraph);
        sanitizeNode(paragraph);
        return;
      }
      if (!richNoteTags.has(element.tagName)) {
        const fragment = document.createDocumentFragment();
        while (element.firstChild) fragment.appendChild(element.firstChild);
        element.replaceWith(fragment);
        sanitizeNode(parent);
        return;
      }

      const originalHref = element.getAttribute('href');
      const originalType = element.getAttribute('type');
      const wasChecked = element.hasAttribute('checked');
      [...element.attributes].forEach((attribute) => element.removeAttribute(attribute.name));
      if (element.tagName === 'A') {
        const href = String(originalHref || '').trim();
        if (/^(https?:|mailto:)/i.test(href)) {
          element.setAttribute('href', href);
          element.setAttribute('target', '_blank');
          element.setAttribute('rel', 'noreferrer');
        } else {
          element.removeAttribute('href');
        }
      } else if (element.tagName === 'INPUT') {
        const isCheckbox = String(originalType || '').toLowerCase() === 'checkbox';
        if (!isCheckbox) {
          element.remove();
          return;
        }
        element.setAttribute('type', 'checkbox');
        element.setAttribute('disabled', '');
        if (wasChecked) element.setAttribute('checked', '');
      }
      sanitizeNode(element);
    });
  }

  sanitizeNode(documentFragment.body);
  return documentFragment.body.innerHTML;
}

export function richNoteToHtml(value, options) {
  const raw = String(value || '');
  if (!raw) return '';
  if (isHtmlRichNote(raw)) return sanitizeRichNote(raw, options);

  return sanitizeRichNote(marked.parse(raw, { async: false, breaks: true, gfm: true }), options);
}

export function richNoteToMarkdown(value, options) {
  const raw = String(value || '');
  if (!raw) return '';
  if (!isHtmlRichNote(raw)) return raw.trim();

  const markup = sanitizeRichNote(raw, options);
  return turndownService.turndown(markup).trim();
}

export function richNoteToPlainText(value, options) {
  const markup = richNoteToHtml(value, options);
  if (!markup || typeof DOMParser === 'undefined') return String(value || '').replace(/<[^>]*>/g, ' ');
  return new DOMParser().parseFromString(markup, 'text/html').body.textContent || '';
}

export function RichTextPreview({
  value,
  emptyText = 'No saved note yet.',
  className = '',
  ariaLabel
}) {
  const markup = richNoteToHtml(value);
  const classes = ['rich-note-preview', className].filter(Boolean).join(' ');

  if (!markup) {
    return (
      <div className={classes} aria-label={ariaLabel}>
        <p className="muted rich-note-empty">{emptyText}</p>
      </div>
    );
  }

  return <div className={classes} aria-label={ariaLabel} dangerouslySetInnerHTML={{ __html: markup }} />;
}

export function RichTextActions({
  isEditing,
  isDirty = false,
  saving = false,
  disabled = false,
  onEdit,
  onCancel,
  onSave,
  editLabel = 'Edit',
  cancelLabel = 'Cancel',
  saveLabel = 'Save note',
  savingLabel = 'Saving...'
}) {
  if (!isEditing) {
    return (
      <button type="button" className="rich-text-edit" disabled={disabled} onClick={onEdit}>
        <Pencil size={16} aria-hidden="true" /> {editLabel}
      </button>
    );
  }

  return (
    <>
      <button type="button" className="rich-text-cancel" disabled={disabled || saving} onClick={onCancel}>
        <RotateCcw size={16} aria-hidden="true" /> {cancelLabel}
      </button>
      <button type="button" className="rich-text-save" disabled={disabled || saving || !isDirty} onClick={onSave}>
        <Save size={16} aria-hidden="true" /> {saving ? savingLabel : saveLabel}
      </button>
    </>
  );
}

export function RichTextNotePanel({
  title,
  headingLevel = 2,
  value,
  draft,
  onChange,
  isEditing,
  isDirty = false,
  saving = false,
  disabled = false,
  onEdit,
  onCancel,
  onSave,
  placeholder,
  emptyText,
  ariaLabel,
  meta,
  extraActions,
  autoFocus = false,
  className = ''
}) {
  const Heading = `h${Math.min(6, Math.max(1, headingLevel))}`;

  return (
    <div className={['rich-text-note-panel', className].filter(Boolean).join(' ')}>
      <header className="rich-text-note-header">
        <Heading>{title}</Heading>
        <div className="rich-text-note-actions">
          {extraActions}
          <RichTextActions
            isEditing={isEditing}
            isDirty={isDirty}
            saving={saving}
            disabled={disabled}
            onEdit={onEdit}
            onCancel={onCancel}
            onSave={onSave}
          />
        </div>
      </header>
      {isEditing ? (
        <RichTextEditor
          ariaLabel={ariaLabel || title}
          autoFocus={autoFocus}
          value={draft}
          onChange={onChange}
          placeholder={placeholder}
        />
      ) : (
        <RichTextPreview
          value={value}
          emptyText={emptyText}
          ariaLabel={`${ariaLabel || title} preview`}
        />
      )}
      {meta ? <p className="rich-text-note-meta">{meta}</p> : null}
    </div>
  );
}

export default function RichTextEditor({
  value,
  onChange,
  placeholder,
  allowHeadings = true,
  autoFocus = false,
  ariaLabel
}) {
  const onChangeRef = useRef(onChange);
  const isLegacyHtml = isHtmlRichNote(value);
  const normalizedValue = isLegacyHtml
    ? sanitizeRichNote(value, { allowHeadings })
    : String(value || '');
  const contentType = isLegacyHtml ? 'html' : 'markdown';

  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  const extensions = useMemo(() => [
    StarterKit.configure({
      heading: allowHeadings ? { levels: [1, 2, 3, 4, 5, 6] } : false,
      link: false,
      underline: false
    }),
    Underline,
    Link.configure({
      openOnClick: false,
      autolink: true,
      linkOnPaste: true,
      HTMLAttributes: {
        rel: 'noreferrer',
        target: '_blank'
      }
    }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Placeholder.configure({ placeholder: placeholder || '' }),
    Markdown
  ], [allowHeadings, placeholder]);

  const editor = useEditor({
    extensions,
    content: normalizedValue,
    contentType,
    editorProps: {
      attributes: {
        class: 'rich-text-surface',
        'aria-label': ariaLabel || placeholder || 'Rich text editor',
        'aria-multiline': 'true',
        'data-placeholder': placeholder || ''
      }
    },
    onUpdate: ({ editor: instance }) => {
      onChangeRef.current(instance.getMarkdown().trim());
    }
  }, [contentType, extensions]);

  useEffect(() => {
    const currentValue = editor?.getMarkdown().trim();
    const nextValue = isLegacyHtml
      ? richNoteToMarkdown(normalizedValue, { allowHeadings })
      : normalizedValue.trim();

    if (editor && !editor.isFocused && currentValue !== nextValue) {
      editor.commands.setContent(normalizedValue, { emitUpdate: false, contentType });
    }
  }, [contentType, editor, isLegacyHtml, normalizedValue, allowHeadings]);

  useEffect(() => {
    if (!autoFocus || !editor) return undefined;

    const frame = window.requestAnimationFrame(() => editor.commands.focus('end'));
    return () => window.cancelAnimationFrame(frame);
  }, [autoFocus, editor]);

  return (
    <MantineRichTextEditor
      editor={editor}
      className="rich-text-editor"
      classNames={{ content: 'rich-text-content', control: 'rich-text-tool' }}
      withCodeHighlightStyles={false}
      withTypographyStyles={false}
    >
      <MantineRichTextEditor.Toolbar
        className="rich-text-toolbar"
        role="toolbar"
        aria-label="Rich text controls"
      >
        {allowHeadings ? (
          <MantineRichTextEditor.ControlsGroup className="rich-text-controls-group">
            <MantineRichTextEditor.H1 />
            <MantineRichTextEditor.H2 />
            <MantineRichTextEditor.H3 />
          </MantineRichTextEditor.ControlsGroup>
        ) : null}
        <MantineRichTextEditor.ControlsGroup className="rich-text-controls-group">
          <MantineRichTextEditor.Bold />
          <MantineRichTextEditor.Italic />
          <MantineRichTextEditor.Underline />
          <MantineRichTextEditor.Strikethrough />
          <MantineRichTextEditor.ClearFormatting />
          <MantineRichTextEditor.Code />
        </MantineRichTextEditor.ControlsGroup>
        <MantineRichTextEditor.ControlsGroup className="rich-text-controls-group">
          <MantineRichTextEditor.BulletList />
          <MantineRichTextEditor.OrderedList />
          <MantineRichTextEditor.TaskList />
          <MantineRichTextEditor.Blockquote />
          <MantineRichTextEditor.CodeBlock />
          <MantineRichTextEditor.Hr />
        </MantineRichTextEditor.ControlsGroup>
        <MantineRichTextEditor.ControlsGroup className="rich-text-controls-group">
          <MantineRichTextEditor.Link />
          <MantineRichTextEditor.Unlink />
          <MantineRichTextEditor.Undo />
          <MantineRichTextEditor.Redo />
        </MantineRichTextEditor.ControlsGroup>
      </MantineRichTextEditor.Toolbar>
      <MantineRichTextEditor.Content />
    </MantineRichTextEditor>
  );
}
