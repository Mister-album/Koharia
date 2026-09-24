package koharia.source.local;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Virtual document-contract rows; no user files or external storage are exposed. */
public final class LocalScanTestProvider extends ContentProvider {
    private String mode = "ready";
    private int childQueries;
    private int documentQueries;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if ("fixture:reset".equals(method)) {
            mode = arg == null ? "ready" : arg;
            childQueries = 0;
            documentQueries = 0;
        } else if ("fixture:stats".equals(method)) {
            result.putInt("children", childQueries);
            result.putInt("documents", documentQueries);
        }
        return result;
    }

    @Override
    public synchronized Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        if ("/root".equals(uri.getPath())) {
            return new MatrixCursor(projection == null
                    ? new String[] { DocumentsContract.Root.COLUMN_ROOT_ID } : projection);
        }
        String[] columns = projection == null ? new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        if ("children".equals(uri.getLastPathSegment())) {
            childQueries++;
            if ("failure".equals(mode)) {
                cursor.close();
                throw new IllegalStateException("Simulated unavailable directory");
            }
            if ("loading".equals(mode)) {
                Bundle extras = new Bundle();
                extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                cursor.setExtras(extras);
            }
            if (!"empty".equals(mode)) {
                for (int i = 0; i < 200; i++) row(cursor, i + ".cbz", false);
            }
        } else {
            documentQueries++;
            String id = DocumentsContract.getDocumentId(uri);
            row(cursor, id, "root".equals(id));
        }
        return cursor;
    }

    private void row(MatrixCursor cursor, String id, boolean directory) {
        Object[] values = new Object[cursor.getColumnCount()];
        String[] names = cursor.getColumnNames();
        for (int i = 0; i < names.length; i++) {
            switch (names[i]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    values[i] = id;
                    break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    values[i] = directory ? DocumentsContract.Document.MIME_TYPE_DIR : "application/zip";
                    break;
                case DocumentsContract.Document.COLUMN_SIZE:
                    values[i] = 4_000_000_000L;
                    break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:
                    values[i] = 12L;
                    break;
                default:
                    values[i] = null;
            }
        }
        cursor.addRow(values);
    }

    @Override
    public String getType(Uri uri) {
        return DocumentsContract.Document.MIME_TYPE_DIR;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only fixture");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only fixture");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only fixture");
    }
}
