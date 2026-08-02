export default function CsvImportPanel({
  file,
  onFileChange,
  onImport,
  loading = false,
  result = null,
  fileLabel = 'Choose CSV file'
}) {
  return (
    <div className="stack-form csv-import-panel">
      <input
        type="file"
        accept=".csv,text/csv"
        aria-label={fileLabel}
        onChange={(event) => onFileChange(event.target.files?.[0] ?? null)}
      />
      <button type="button" onClick={onImport} disabled={!file || loading}>
        {loading ? 'Importing...' : 'Import CSV'}
      </button>
      {result ? (
        <div className="import-result csv-import-result" aria-live="polite">
          <p><span>Total rows</span><strong>{result.totalRows}</strong></p>
          <p><span>Imported</span><strong>{result.importedRows}</strong></p>
          <p><span>Skipped</span><strong>{result.skippedRows}</strong></p>
          <p><span>Failed</span><strong>{result.failedRows}</strong></p>
        </div>
      ) : null}
    </div>
  );
}
