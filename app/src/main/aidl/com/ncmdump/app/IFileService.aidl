// AIDL for the Shizuku shell-process file service.
// Implemented by FileService, which runs under the shell uid (2000) and can
// therefore read /storage/emulated/0/Android/data/** that the app process cannot.
package com.ncmdump.app;

interface IFileService {
    // Special transaction id reserved by the Shizuku server to stop the service.
    void destroy() = 16777114;

    boolean isDirectory(String path) = 1;

    // Leaf names of every *.ncm file directly under dirPath (non-recursive).
    String[] listNcmNames(String dirPath) = 2;

    // Open a file read-only and hand the descriptor back across the binder.
    ParcelFileDescriptor openRead(String filePath) = 3;

    boolean deleteFile(String filePath) = 4;
}
