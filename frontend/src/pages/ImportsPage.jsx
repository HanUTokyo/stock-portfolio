export default function ImportsPage({ importFile, setImportFile, importLoading, importResult, onDryRun, onImport, onDownloadErrors }) {
  return (
    <div className="import-panel">
      <h2>CSV Import Center</h2>
      <p className="muted">Upload your trade CSV, run dry-run validation, then import or export failed rows.</p>

      <div className="action-grid">
        <input
          type="file"
          accept=".csv,text/csv"
          onChange={(e) => setImportFile(e.target.files?.[0] || null)}
        />
        <div className="button-row">
          <button type="button" onClick={onDryRun} disabled={importLoading}>Dry-Run Validate</button>
          <button type="button" onClick={onImport} disabled={importLoading}>Import CSV</button>
          <button type="button" onClick={onDownloadErrors}>Export Failed Rows CSV</button>
        </div>
      </div>

      {importResult ? (
        <div className="import-result">
          <p>
            dryRun={String(importResult.dryRun)} | total={importResult.totalRows} | imported={importResult.importedRows} |
            skipped={importResult.skippedRows} | failed={importResult.failedRows}
          </p>
          {importResult.sampleErrors?.length ? (
            <ul>
              {importResult.sampleErrors.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          ) : (
            <p>No sample errors.</p>
          )}
        </div>
      ) : null}

      {!importFile ? <p className="muted">Select a CSV file to begin.</p> : null}
    </div>
  );
}
