/*
 * Copyright (c) 2017 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.transport.remotefilesystem.server;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.VFS;
import org.apache.commons.vfs2.provider.UriParser;
import org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.transport.remotefilesystem.Constants;
import org.wso2.carbon.transport.remotefilesystem.exception.RemoteFileSystemConnectorException;
import org.wso2.carbon.transport.remotefilesystem.listener.RemoteFileSystemListener;
import org.wso2.carbon.transport.remotefilesystem.server.util.FileTransportUtils;
import org.wso2.carbon.transport.remotefilesystem.server.util.ThreadPoolFactory;

import java.io.File;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides the capability to process a file and move/delete it afterwards.
 */
public class RemoteFileSystemConsumer {

    private static final Logger log = LoggerFactory.getLogger(RemoteFileSystemConsumer.class);

    private Map<String, String> fileProperties;
    private FileSystemManager fsManager = null;
    private String serviceName;
    private RemoteFileSystemListener remoteFileSystemListener;
    private String listeningDirURI; // The URI of the currently listening directory
    private FileObject listeningDir; // The directory we are currently listening to
    private FileSystemOptions fso;
    private int threadPoolSize = 0;
    private ThreadPoolFactory threadPool;
    private int fileProcessCount;
    private int processCount;
    private String fileNamePattern = null;
    private String postProcessAction;
    private String postFailureAction;

    private List<String> processed = new ArrayList<>();
    private List<String> processPending = new ArrayList<>();
    private List<String> failed = new ArrayList<>(); // Already processed, but failed to move or delete

    /**
     * Constructor for the RemoteFileSystemConsumer.
     *
     * @param id                Name of the service that creates the consumer
     * @param fileProperties    Map of property values
     * @param listener  RemoteFileSystemListener instance to send callback
     * @throws RemoteFileSystemConnectorException if unable to start the connect to the remote server
     */
    public RemoteFileSystemConsumer(String id, Map<String, String> fileProperties, RemoteFileSystemListener listener)
            throws RemoteFileSystemConnectorException {
        this.serviceName = id;
        this.fileProperties = fileProperties;
        this.remoteFileSystemListener = listener;
        setupParams();
        try {
            fsManager = VFS.getManager();
            Map<String, String> options = parseSchemeFileOptions(listeningDirURI);
            fso = FileTransportUtils.attachFileSystemOptions(options, fsManager);
            // TODO: Make this and other file related configurations configurable
            if (options != null && Constants.SCHEME_FTP.equals(options.get(Constants.SCHEME))) {
                FtpFileSystemConfigBuilder.getInstance().setPassiveMode(fso, true);
            }
            listeningDir = fsManager.resolveFile(listeningDirURI, fso);
            if (!listeningDir.isWriteable()) {
                postProcessAction = Constants.ACTION_NONE;
            }
            FileType fileType = getFileType(listeningDir);
            if (fileType != FileType.FOLDER) {
                String errorMsg = "[" + serviceName + "] File system server connector is used to " +
                        "listen to a folder. But the given path does not refer to a folder.";
                final RemoteFileSystemConnectorException exception
                        = new RemoteFileSystemConnectorException(errorMsg);
                remoteFileSystemListener.onError(exception);
                throw exception;
            }
            //Initialize the thread executor based on properties
            threadPool = new ThreadPoolFactory(threadPoolSize);
        } catch (FileSystemException e) {
            remoteFileSystemListener.onError(e);
            throw new RemoteFileSystemConnectorException("[" + serviceName + "] Unable to initialize " +
                    "the connection with server.", e);
        }
    }

    /**
     * Setup the required transport parameters from properties provided.
     */
    private void setupParams() throws RemoteFileSystemConnectorException {
        listeningDirURI = fileProperties.get(Constants.TRANSPORT_FILE_URI);
        if (listeningDirURI == null) {
            final RemoteFileSystemConnectorException exception = new RemoteFileSystemConnectorException(
                    Constants.TRANSPORT_FILE_URI + " is a mandatory parameter for FTP transport.");
            remoteFileSystemListener.onError(exception);
            throw exception;
        } else if (listeningDirURI.trim().isEmpty()) {
            final RemoteFileSystemConnectorException e =
                    new RemoteFileSystemConnectorException("[" + serviceName + "] "
                            + Constants.TRANSPORT_FILE_URI + " parameter cannot be empty for FTP transport.");
            remoteFileSystemListener.onError(e);
            throw e;
        }
        String strParallel;
        if ((strParallel = fileProperties.get(Constants.PARALLEL)) != null) {
            boolean parallelProcess = Boolean.parseBoolean(strParallel);
            if (parallelProcess) {
                String strPoolSize;
                if ((strPoolSize = fileProperties.get(Constants.THREAD_POOL_SIZE)) != null) {
                    threadPoolSize = Integer.parseInt(strPoolSize);
                } else {
                    threadPoolSize = 5;
                }
            }
        }
        String strProcessCount;
        if ((strProcessCount = fileProperties.get(Constants.FILE_PROCESS_COUNT)) != null) {
            fileProcessCount = Integer.parseInt(strProcessCount);
        }
        if (fileProperties.get(Constants.ACTION_AFTER_FAILURE) != null) {
            switch (fileProperties.get(Constants.ACTION_AFTER_FAILURE)) {
                case Constants.ACTION_MOVE:
                    postFailureAction = Constants.ACTION_MOVE;
                    break;
                case Constants.ACTION_NONE:
                    postFailureAction = Constants.ACTION_NONE;
                    break;
                case Constants.ACTION_DELETE:
                    postFailureAction = Constants.ACTION_DELETE;
                    break;
                default:
                    final RemoteFileSystemConnectorException e =
                            new RemoteFileSystemConnectorException("[" + serviceName + "] "
                                    + Constants.ACTION_AFTER_FAILURE + " parameter cannot be empty. " +
                                    "Accepted values are [" + Constants.ACTION_NONE + ", " +
                                    Constants.ACTION_MOVE + ", " +
                                    Constants.ACTION_DELETE + "]");
                    remoteFileSystemListener.onError(e);
                    throw e;
            }
        }
        if (fileProperties.get(Constants.ACTION_AFTER_PROCESS) != null) {
            switch (fileProperties.get(Constants.ACTION_AFTER_PROCESS)) {
                case Constants.ACTION_MOVE:
                    postProcessAction = Constants.ACTION_MOVE;
                    break;
                case Constants.ACTION_NONE:
                    postProcessAction = Constants.ACTION_NONE;
                    break;
                case Constants.ACTION_DELETE:
                    postProcessAction = Constants.ACTION_DELETE;
                    break;
                default:
                    final RemoteFileSystemConnectorException e =
                            new RemoteFileSystemConnectorException("[" + serviceName + "] "
                                    + Constants.ACTION_AFTER_PROCESS + " parameter cannot be empty. " +
                                    "Accepted values are [" + Constants.ACTION_NONE + ", " +
                                    Constants.ACTION_MOVE + ", " +
                                    Constants.ACTION_DELETE + "]");
                    remoteFileSystemListener.onError(e);
                    throw e;
            }
        }
        String strPattern;
        if ((strPattern = fileProperties.get(Constants.FILE_NAME_PATTERN)) != null) {
            fileNamePattern = strPattern;
        }
    }

    /**
     * Get file options specific to a particular scheme.
     *
     * @param fileURI   URI of file to get file options
     * @return          File options related to scheme.
     */
    private Map<String, String> parseSchemeFileOptions(String fileURI) {
        String scheme;
        if ((scheme = UriParser.extractScheme(fileURI)) == null) {
            return null;
        }
        HashMap<String, String> schemeFileOptions = new HashMap<>();
        schemeFileOptions.put(Constants.SCHEME, scheme);
        if (scheme.equals(Constants.SCHEME_SFTP)) {
            for (Constants.SftpFileOption option : Constants.SftpFileOption.values()) {
                String strValue = fileProperties.get(Constants.SFTP_PREFIX + option.toString());
                if (strValue != null && !strValue.isEmpty()) {
                    schemeFileOptions.put(option.toString(), strValue);
                }
            }
        }
        return schemeFileOptions;
    }

    /**
     * Do the file processing operation for the given set of properties. Do the
     * checks and pass the control to file system processor thread/threads.
     *
     * @throws RemoteFileSystemConnectorException for all the error situation.
     */
    public void consume() throws RemoteFileSystemConnectorException {
        if (log.isDebugEnabled()) {
            log.debug("Thread name: " + Thread.currentThread().getName());
            log.debug("File System Consumer hashcode: " + this.hashCode());
            log.debug("Polling for directory or file: " + FileTransportUtils.maskURLPassword(listeningDirURI));
        }
        //Resetting the process count, used to control number of files processed per batch
        processCount = 0;
        // If file/folder found proceed to the processing stage
        try {
            boolean isFileExists; // Initially assume that the file doesn't exist
            boolean isFileReadable; // Initially assume that the file is not readable
            listeningDir.refresh();
            isFileExists = listeningDir.exists();
            isFileReadable = listeningDir.isReadable();
            if (isFileExists && isFileReadable) {
                FileObject[] children = null;
                try {
                    children = listeningDir.getChildren();
                } catch (FileSystemException ignored) {
                    if (log.isDebugEnabled()) {
                        log.debug("[" + serviceName + "] The file does not exist, or is not a folder, or an error " +
                                "has occurred when trying to list the children. File URI : " +
                                FileTransportUtils.maskURLPassword(listeningDirURI), ignored);
                    }
                }
                if (children == null || children.length == 0) {
                    if (log.isDebugEnabled()) {
                        log.debug("[" + serviceName + "] Folder at " +
                                FileTransportUtils.maskURLPassword(listeningDirURI) + " is empty.");
                    }
                } else {
                    directoryHandler(children);
                }
            } else {
                remoteFileSystemListener.onError(new RemoteFileSystemConnectorException(
                        "[" + serviceName + "] Unable to access or read file or directory : " +
                                FileTransportUtils.maskURLPassword(listeningDirURI) + ". Reason: " +
                                (isFileExists ? "The file can not be read!" : "The file does not exist!")));
            }
        } catch (FileSystemException e) {
            remoteFileSystemListener.onError(e);
            throw new RemoteFileSystemConnectorException("[" + serviceName + "] Unable to get details " +
                    "from remote server.", e);
        } finally {
            try {
                if (listeningDir != null) {
                    listeningDir.close();
                }
            } catch (FileSystemException e) {
                log.warn("[" + serviceName + "] Could not close file at URI: " +
                        FileTransportUtils.maskURLPassword(listeningDirURI), e);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[" + serviceName + "] End : Scanning directory or file : " +
                    FileTransportUtils.maskURLPassword(listeningDirURI));
        }
    }

    /**
     * Handle directory with child elements.
     *
     * @param children The array containing child elements of a folder
     */
    private void directoryHandler(FileObject[] children) throws RemoteFileSystemConnectorException {
        // Sort the files according to given properties
        String strSortParam = fileProperties.get(Constants.FILE_SORT_PARAM);

        // TODO: rethink the way the string constants are handled
        if (strSortParam != null && !Constants.ACTION_NONE.equals(strSortParam)) {
            if (log.isDebugEnabled()) {
                log.debug("Starting to sort the files in folder: " +
                        FileTransportUtils.maskURLPassword(listeningDirURI));
            }
            String strSortOrder = fileProperties.get(Constants.FILE_SORT_ORDER);
            boolean bSortOrderAscending = true;
            if (strSortOrder != null) {
                bSortOrderAscending = Boolean.parseBoolean(strSortOrder);
            }
            if (log.isDebugEnabled()) {
                log.debug("Sorting the files by : " + strSortOrder + ". (" + bSortOrderAscending + ")");
            }
            switch (strSortParam) {
                case Constants.FILE_SORT_VALUE_NAME:
                    if (bSortOrderAscending) {
                        Arrays.sort(children, new FileNameAscComparator());
                    } else {
                        Arrays.sort(children, new FileNameDesComparator());
                    }
                    break;
                case Constants.FILE_SORT_VALUE_SIZE:
                    if (bSortOrderAscending) {
                        Arrays.sort(children, new FileSizeAscComparator());
                    } else {
                        Arrays.sort(children, new FileSizeDesComparator());
                    }
                    break;
                case Constants.FILE_SORT_VALUE_LASTMODIFIEDTIMESTAMP:
                    if (bSortOrderAscending) {
                        Arrays.sort(children, new FileLastModifiedTimestampAscComparator());
                    } else {
                        Arrays.sort(children, new FileLastModifiedTimestampDesComparator());
                    }
                    break;
                default:
                    log.warn("[" + serviceName + "] Invalid value given for " +
                            Constants.FILE_SORT_PARAM + " parameter. " +
                             " Expected one of the values: " + Constants.FILE_SORT_VALUE_NAME + ", " +
                             Constants.FILE_SORT_VALUE_SIZE + " or " + Constants.FILE_SORT_VALUE_LASTMODIFIEDTIMESTAMP +
                             ". Found: " + strSortParam);
                    break;
            }
            if (log.isDebugEnabled()) {
                log.debug("End sorting the files.");
            }
        }
        for (FileObject child : children) {
            if (fileProcessCount != 0 && processCount > fileProcessCount) {
                return;
            }
            if (child.getName().getBaseName().endsWith(".lock") || child.getName().getBaseName().endsWith(".fail")) {
                continue;
            }
            if (!(fileNamePattern == null || child.getName().getBaseName().matches(fileNamePattern))) {
                if (log.isDebugEnabled()) {
                    log.debug("File " + FileTransportUtils.maskURLPassword(listeningDir.getName().getBaseName()) +
                              " is not processed because it did not match the specified pattern.");
                }
            } else {
                FileType childType = getFileType(child);
                if (childType == FileType.FOLDER) {
                    FileObject[] c = null;
                    try {
                        c = child.getChildren();
                    } catch (FileSystemException ignored) {
                        if (log.isDebugEnabled()) {
                            log.debug("The file does not exist, or is not a folder, or an error " +
                                      "has occurred when trying to list the children. File URI : " +
                                      FileTransportUtils.maskURLPassword(listeningDirURI), ignored);
                        }
                    }

                    // if this is a file that would translate to a single message
                    if (c == null || c.length == 0) {
                        if (log.isDebugEnabled()) {
                            log.debug("Folder at " + FileTransportUtils.maskURLPassword(child.getName().getURI()) +
                                      " is empty.");
                        }
                    } else {
                        directoryHandler(c);
                    }
                    postProcess(child, true);
                } else {
                    fileHandler(child);
                }
            }
        }
    }

    /**
     * Process a single file.
     *
     * @param file A single file to be processed
     */
    private void fileHandler(FileObject file) {
        String uri = file.getName().getURI();
        synchronized (this) {
            if (postProcessAction.equals(Constants.ACTION_NONE) && processed.contains(uri)) {
                if (log.isDebugEnabled()) {
                    log.debug("The file: " + FileTransportUtils.maskURLPassword(uri) + " is already processed");
                }
                return;
            }
        }
        if (!postProcessAction.equals(Constants.ACTION_NONE) && isFailRecord(file)) {
            // it is a failed record
            try {
                postProcess(file, true);
            } catch (RemoteFileSystemConnectorException e) {
                log.error("File object '" + FileTransportUtils.maskURLPassword(uri) +
                          "'could not complete action " + postProcessAction +
                          ", will remain in \"fail\" state", e);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Processing file: " + FileTransportUtils.maskURLPassword(file.getName().getBaseName()));
            }
            if (!processPending.contains(uri)) {
                // Temporary block adding same file to the queue. File lock will acquire in the spawn thread.
                processPending.add(uri);
                RemoteFileSystemProcessor fsp =
                        new RemoteFileSystemProcessor(remoteFileSystemListener, serviceName, file,
                                this, postProcessAction, fsManager, fso);
                threadPool.execute(fsp);
                processCount++;
            }
        }
    }

    /**
     * Do the post processing actions.
     *
     * @param file The file object which needs to be post processed
     * @param processSucceed Whether processing of file passed or not.
     */
    synchronized void postProcess(FileObject file, boolean processSucceed) throws RemoteFileSystemConnectorException {
        String moveToDirectoryURI = null;
        FileType fileType = getFileType(file);
        if (processSucceed) {
            if (postProcessAction.equals(Constants.ACTION_MOVE)) {
                moveToDirectoryURI = fileProperties.get(Constants.MOVE_AFTER_PROCESS);
            }
        } else {
            if (postFailureAction.equals(Constants.ACTION_MOVE)) {
                moveToDirectoryURI = fileProperties.get(Constants.MOVE_AFTER_FAILURE);
            }
        }
        if (moveToDirectoryURI != null) {
            try {
                if (getFileType(fsManager.resolveFile(moveToDirectoryURI, fso)) == FileType.FILE) {
                    moveToDirectoryURI = null;
                    if (processSucceed) {
                        postProcessAction = Constants.ACTION_NONE;
                    } else {
                        postFailureAction = Constants.ACTION_NONE;
                    }
                    log.warn("[" + serviceName + "] Cannot move file because provided location is not a folder." +
                            " File is kept at source.");
                }
            } catch (FileSystemException e) {
                remoteFileSystemListener.onError(new RemoteFileSystemConnectorException(
                        "Error occurred when resolving move destination file: " +
                                FileTransportUtils.maskURLPassword(listeningDirURI), e));
            }
        }
        if (postProcessAction.equals(Constants.ACTION_NONE) && fileType == FileType.FOLDER) {
            return;
        }
        try {
            if (!(moveToDirectoryURI == null || fileType == FileType.FOLDER)) {
                FileObject moveToDirectory;
                String relativeName = file.getName().getURI().split(listeningDir.getName().getURI())[1];
                int index = relativeName.lastIndexOf(File.separator);
                moveToDirectoryURI += relativeName.substring(0, index);
                moveToDirectory = fsManager.resolveFile(moveToDirectoryURI, fso);
                String prefix;
                if (fileProperties.get(Constants.MOVE_TIMESTAMP_FORMAT) != null) {
                    prefix = new SimpleDateFormat(fileProperties.get(Constants.MOVE_TIMESTAMP_FORMAT))
                            .format(new Date());
                } else {
                    prefix = "";
                }
                //Forcefully create the folder(s) if does not exists
                String strForceCreateFolder = fileProperties.get(Constants.FORCE_CREATE_FOLDER);
                if (strForceCreateFolder != null && strForceCreateFolder.equalsIgnoreCase("true") &&
                    !moveToDirectory.exists()) {
                    moveToDirectory.createFolder();
                }
                FileObject destination = moveToDirectory.resolveFile(prefix + file.getName().getBaseName());
                if (log.isDebugEnabled()) {
                    log.debug("Moving file: " + FileTransportUtils.maskURLPassword(file.getName().getBaseName()));
                }
                try {
                    file.moveTo(destination);
                    if (isFailRecord(file)) {
                        releaseFail(file);
                    }
                } catch (FileSystemException e) {
                    if (!isFailRecord(file)) {
                        markFailRecord(file);
                    }
                    remoteFileSystemListener.onError(new RemoteFileSystemConnectorException(
                            "[" + serviceName + "] Error moving file: " +
                                    FileTransportUtils.maskURLPassword(file.toString()) + " to " +
                                    FileTransportUtils.maskURLPassword(moveToDirectoryURI), e));
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Deleting file: " + FileTransportUtils.maskURLPassword(file.getName().getBaseName()));
                }
                try {
                    if (!file.delete()) {
                        if (log.isDebugEnabled()) {
                            log.debug("Could not delete file: " +
                                      FileTransportUtils.maskURLPassword(file.getName().getBaseName()));
                        }
                    } else {
                        if (isFailRecord(file)) {
                            releaseFail(file);
                        }
                    }
                } catch (FileSystemException e) {
                    remoteFileSystemListener.onError(new RemoteFileSystemConnectorException(
                            "[" + serviceName + "] Could not delete file: " + FileTransportUtils.maskURLPassword(
                                    file.getName().getBaseName()), e));
                }
            }
        } catch (FileSystemException e) {
            if (!isFailRecord(file)) {
                markFailRecord(file);
                remoteFileSystemListener.onError(new RemoteFileSystemConnectorException(
                        "[" + serviceName + "] Error resolving directory to move file : " +
                                FileTransportUtils.maskURLPassword(moveToDirectoryURI), e));
            }
        }
    }

    /**
     * This method will stop all the threads that initiate to handle files through {@link RemoteFileSystemProcessor}.
     * No of threads will be define using 'threadPoolSize' config.
     */
    public void stopThreadPool() {
        threadPool.stop();
    }

    /**
     * Determine whether file object is a file or a folder.
     *
     * @param fileObject    File to get the type of
     * @return              FileType of given file
     */
    private FileType getFileType(FileObject fileObject) throws RemoteFileSystemConnectorException {
        try {
            return fileObject.getType();
        } catch (FileSystemException e) {
            remoteFileSystemListener.onError(new RemoteFileSystemConnectorException(
                    "[" + serviceName + "] Error occurred when determining whether file: " +
                            FileTransportUtils.maskURLPassword(fileObject.getName().getURI()) +
                            " is a file or a folder", e));
        }

        return FileType.IMAGINARY;
    }

    /**
     * Mark a record as a failed record.
     *
     * @param fo    File to be marked as failed
     */
    private synchronized void markFailRecord(FileObject fo) {
        String fullPath = fo.getName().getURI();
        if (failed.contains(fullPath)) {
            if (log.isDebugEnabled()) {
                log.debug("File: " + FileTransportUtils.maskURLPassword(fullPath) +
                                  " is already marked as a failed record.");
            }
            return;
        }
        failed.add(fullPath);
    }

    /**
     * Determine whether a file is a failed record.
     *
     * @param fo    File to determine whether failed
     * @return      true if file is a failed file
     */
    private boolean isFailRecord(FileObject fo) {
        return failed.contains(fo.getName().getURI());
    }

    /**
     * Releases a file from its failed state.
     *
     * @param fo    File to release from failed state
     */
    private synchronized void releaseFail(FileObject fo) {
        String fullPath = fo.getName().getURI();
        failed.remove(fullPath);
    }

    /**
     * Mark a file as processed.
     *
     * @param uri URI of the file to be named as processed
     */
    synchronized void markProcessed(String uri) {
        processed.add(uri);
    }

    /**
     * Removed file that marked as process pending.
     *
     * @param uri URI of the file
     */
    void removeProcessPending(String uri) {
        processPending.remove(uri);
    }

    /**
     * Comparator classes used to sort the files according to user input.
     */
    private static class FileNameAscComparator implements Comparator<FileObject>, Serializable {
        private static final long serialVersionUID = 4555707486520285162L;

        @Override
        public int compare(FileObject o1, FileObject o2) {
            return o1.getName().compareTo(o2.getName());
        }
    }

    private static class FileLastModifiedTimestampAscComparator implements Comparator<FileObject>, Serializable {
        private static final long serialVersionUID = 1;
        @Override
        public int compare(FileObject o1, FileObject o2) {
            Long lDiff = 0L;
            try {
                lDiff = o1.getContent().getLastModifiedTime() - o2.getContent().getLastModifiedTime();
            } catch (FileSystemException e) {
                log.warn("Unable to compare last modified timestamp of the two files.", e);
            }
            return lDiff.intValue();
        }
    }

    private static class FileSizeAscComparator implements Comparator<FileObject>, Serializable {
        private static final long serialVersionUID = 1;
        @Override
        public int compare(FileObject o1, FileObject o2) {
            Long lDiff = 0L;
            try {
                lDiff = o1.getContent().getSize() - o2.getContent().getSize();
            } catch (FileSystemException e) {
                log.warn("Unable to compare size of the two files.", e);
            }
            return lDiff.intValue();
        }
    }

    private static class FileNameDesComparator implements Comparator<FileObject>, Serializable {
        private static final long serialVersionUID = -6544250542596965005L;

        @Override
        public int compare(FileObject o1, FileObject o2) {
            return o2.getName().compareTo(o1.getName());
        }
    }

    private static class FileLastModifiedTimestampDesComparator implements Comparator<FileObject>, Serializable {
        private static final long serialVersionUID = -8977991297439935929L;

        @Override
        public int compare(FileObject o1, FileObject o2) {
            Long lDiff = 0L;
            try {
                lDiff = o2.getContent().getLastModifiedTime() - o1.getContent().getLastModifiedTime();
            } catch (FileSystemException e) {
                log.warn("Unable to compare last modified timestamp of the two files.", e);
            }
            return lDiff.intValue();
        }
    }

    private static class FileSizeDesComparator implements Comparator<FileObject>, Serializable {
        private static final long serialVersionUID = -2289143315156186742L;

        @Override
        public int compare(FileObject o1, FileObject o2) {
            Long lDiff = 0L;
            try {
                lDiff = o2.getContent().getSize() - o1.getContent().getSize();
            } catch (FileSystemException e) {
                log.warn("Unable to compare size of the two files.", e);
            }
            return lDiff.intValue();
        }
    }
}
