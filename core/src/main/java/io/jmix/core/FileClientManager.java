/*
 * Copyright 2021 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.jmix.core;

import com.google.common.base.Strings;
import io.jmix.core.common.util.URLEncodeUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.Nullable;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;

@Component("core_FileClientManager")
public class FileClientManager {

    private static final Logger log = LoggerFactory.getLogger(FileClientManager.class);

    @Autowired
    FileStorageLocator fileStorageLocator;

    public void downloadAndWriteResponse(FileRef fileReference, String fileStorageName,
                                                Boolean attachment, HttpServletResponse response) throws FileStorageException {
        FileStorage fileStorage = getFileStorageByNameOrDefault(fileStorageName);
        //check if a file by the given reference exists
        if (!fileStorage.fileExists(fileReference)) {
            throw new FileClientException("File not found", "File not found. File reference: " +
                    fileReference, HttpStatus.NOT_FOUND);
        }

        try {
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
            response.setHeader("Content-Type", fileReference.getContentType());

            String filename = fileReference.getFileName();
            String contentDisposition = BooleanUtils.isTrue(attachment) ? "attachment" : "inline";
            if (StringUtils.isNotEmpty(filename)) {
                contentDisposition += "; filename=\"" + URLEncodeUtils.encodeUtf8(filename) + "\"";
            }
            response.setHeader("Content-Disposition", contentDisposition);

            ServletOutputStream os = response.getOutputStream();
            InputStream is = fileStorage.openStream(fileReference);
            IOUtils.copy(is, os);
            os.flush();

        } catch (Exception e) {
            log.error("Error on downloading the file {}", fileReference, e);
            throw new FileClientException("Error on downloading the file", "", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    protected FileStorage getFileStorageByNameOrDefault(@Nullable String storageName) {
        if (Strings.isNullOrEmpty(storageName)) {
            return fileStorageLocator.getDefault();
        } else {
            try {
                return fileStorageLocator.getByName(storageName);
            } catch (IllegalArgumentException e) {
                throw new FileClientException("Invalid file reference",
                        String.format("Cannot find FileStorage with name: '%s'", storageName),
                        HttpStatus.BAD_REQUEST,
                        e);
            }
        }
    }

    protected FileRef uploadToFileStorage(FileStorage fileStorage, InputStream is, String fileName) {
        try {
            return fileStorage.saveStream(fileName, is);
        } catch (FileStorageException e) {
            throw new FileClientException("Unable to upload file to FileStorage",
                    "Unable to upload file to FileStorage: " + fileName,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    e);
        }
    }

    protected ResponseEntity<FileInfoResponse> createFileInfoResponseEntity(HttpServletRequest request,
                                                                                   FileRef fileRef, String filename, long size) {
        FileInfoResponse fileInfo = new FileInfoResponse(fileRef.toString(), filename, size);

        UriComponents uriComponents = UriComponentsBuilder.fromHttpUrl(request.getRequestURL().toString())
                .queryParam("fileRef", URLEncodeUtils.encodeUtf8(fileRef.toString()))
                .buildAndExpand();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setLocation(URI.create(uriComponents.toUriString()));
        return new ResponseEntity<>(fileInfo, httpHeaders, HttpStatus.CREATED);
    }

    public ResponseEntity<FileInfoResponse> multipartFileUpload(MultipartFile file, String name,
                                                                       String fileStorageName, HttpServletRequest request) {
        try {
            if (Strings.isNullOrEmpty(name)) {
                name = file.getOriginalFilename();
            }
            name = Objects.toString(name, "");

            long size = file.getSize();

            InputStream is = file.getInputStream();
            FileStorage fileStorage = getFileStorageByNameOrDefault(fileStorageName);
            FileRef fileRef = uploadToFileStorage(fileStorage, is, name);

            return createFileInfoResponseEntity(request, fileRef, name, size);
        } catch (Exception e) {
            log.error("File upload failed", e);
            throw new FileClientException("File upload failed", "File upload failed", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    public ResponseEntity<FileInfoResponse> fileUpload(String name, String fileStorageName,
                                                              HttpServletRequest request) {
        try {
            String contentLength = request.getHeader("Content-Length");

            long size = 0;
            try {
                size = Long.parseLong(contentLength);
            } catch (NumberFormatException ignored) {
            }

            ServletInputStream is = request.getInputStream();
            name = Objects.toString(name, "");
            FileStorage fileStorage = getFileStorageByNameOrDefault(fileStorageName);
            FileRef fileRef = uploadToFileStorage(fileStorage, is, name);

            return createFileInfoResponseEntity(request, fileRef, name, size);
        } catch (Exception e) {
            log.error("File upload failed", e);
            throw new FileClientException("File upload failed", "File upload failed", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }
}
