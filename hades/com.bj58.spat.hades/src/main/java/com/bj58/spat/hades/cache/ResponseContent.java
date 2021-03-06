 /*
 *  Copyright Beijing 58 Information Technology Co.,Ltd.
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package com.bj58.spat.hades.cache;

import java.io.*;

import java.util.Locale;
import java.util.zip.GZIPInputStream;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;


/**
 * 用于操作Memcache的客户端工具类。
 * 可以将servlet response进行序列化方便用于cache存储。
 * @author Service Platform Architecture Team (spat@58.com)
 *
 */
public class ResponseContent implements Serializable {
    private transient ByteArrayOutputStream bout = new ByteArrayOutputStream(1000);
    private Locale locale = null;
    private String contentEncoding = null;
    private String contentType = null;
    private byte[] content = null;
    private long expires = Long.MAX_VALUE;
    private long lastModified = -1;
    private long maxAge = -60;

    public String getContentType() {
        return contentType;
    }
    
    /**
     * Set the content type. We capture this so that when we serve this
     * data from cache, we can set the correct content type on the response.
     */
    public void setContentType(String value) {
        contentType = value;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long value) {
        lastModified = value;
    }

    public String getContentEncoding() {
        return contentEncoding;
    }

    public void setContentEncoding(String contentEncoding) {
        this.contentEncoding = contentEncoding;
    }

    /**
     * Set the Locale. We capture this so that when we serve this data from
     * cache, we can set the correct locale on the response.
     */
    public void setLocale(Locale value) {
        locale = value;
    }

    /**
     * @return the expires date and time in miliseconds when the content will be stale
     */
    public long getExpires() {
        return expires;
    }

    /**
     * Sets the expires date and time in miliseconds.
     * @param value time in miliseconds when the content will expire
     */
    public void setExpires(long value) {
        expires = value;
    }

	/**
	 * Returns the max age of the content in miliseconds. If expires header and cache control are
	 * enabled both, both will be equal. 
	 * @return the max age of the content in miliseconds, if -1 max-age is disabled
	 */
	public long getMaxAge() {
		return maxAge;
	}

	/**
	 * Sets the max age date and time in miliseconds. If the parameter is -1, the max-age parameter
	 * won't be set by default in the Cache-Control header.
	 * @param value sets the intial
	 */
	public void setMaxAge(long value) {
		maxAge = value;
	}

    /**
     * Get an output stream. This is used by the {@link SplitServletOutputStream}
     * to capture the original (uncached) response into a byte array.
     * @return the original (uncached) response, returns null if response is already committed.
     */
    public OutputStream getOutputStream() {
        return bout;
    }

    /**
     * Gets the size of this cached content.
     *
     * @return The size of the content, in bytes. If no content
     * exists, this method returns <code>-1</code>.
     */
    public int getSize() {
        return (content != null) ? content.length : (-1);
    }

    /**
     * Called once the response has been written in its entirety. This
     * method commits the response output stream by converting the output
     * stream into a byte array.
     */
    public void commit() {
        if (bout != null) {
            content = bout.toByteArray();
            bout = null;
        }
    }

    /**
     * Writes this cached data out to the supplied <code>ServletResponse</code>.
     *
     * @param response The servlet response to output the cached content to.
     * @throws IOException
     */
    public void writeTo(ServletResponse response) throws IOException {
        writeTo(response, false, false);
    }

    /**
     * Writes this cached data out to the supplied <code>ServletResponse</code>.
     *
     * @param response The servlet response to output the cached content to.
     * @param fragment is true if this content a fragment or part of a page
     * @param acceptsGZip is true if client browser supports gzip compression
     * @throws IOException
     */
    public void writeTo(ServletResponse response, boolean fragment, boolean acceptsGZip) throws IOException {
        //Send the content type and data to this response
        if (contentType != null) {
            response.setContentType(contentType);
        }
        
        if (fragment) {
            // Don't support gzip compression if the content is a fragment of a page
            acceptsGZip = false;
        } else {
            // add special headers for a complete page
            if (response instanceof HttpServletResponse) {
                HttpServletResponse httpResponse = (HttpServletResponse) response;
                
                // add the last modified header
                if (lastModified != -1) {
                    httpResponse.setDateHeader(HttpCache.HEADER_LAST_MODIFIED, lastModified);
                }
                
                // add the expires header
                if (expires != Long.MAX_VALUE) {
                    httpResponse.setDateHeader(HttpCache.HEADER_EXPIRES, expires);
                }
                
                // add the cache-control header for max-age
                if (maxAge == HttpCache.MAX_AGE_NO_INIT || maxAge == HttpCache.MAX_AGE_TIME) {
                	// do nothing
                } else if (maxAge > 0) { // set max-age based on life time
                	long currentMaxAge = maxAge / 1000 - System.currentTimeMillis() / 1000;
                	if (currentMaxAge < 0) {
                		currentMaxAge = 0;
                	}
                	httpResponse.addHeader(HttpCache.HEADER_CACHE_CONTROL, "max-age=" + currentMaxAge);
                } else {
                	httpResponse.addHeader(HttpCache.HEADER_CACHE_CONTROL, "max-age=" + (-maxAge));
                }
                
            }
        }

        if (locale != null) {
            response.setLocale(locale);
        }

        OutputStream out = new BufferedOutputStream(response.getOutputStream());

        if (isContentGZiped()) {
            if (acceptsGZip) {
                ((HttpServletResponse) response).addHeader(HttpCache.HEADER_CONTENT_ENCODING, "gzip");
                response.setContentLength(content.length);
                out.write(content);
            } else {
                // client doesn't support, so we have to uncompress it
                ByteArrayInputStream bais = new ByteArrayInputStream(content);
                GZIPInputStream zis = new GZIPInputStream(bais);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int numBytesRead = 0;
                byte[] tempBytes = new byte[4196];

                while ((numBytesRead = zis.read(tempBytes, 0, tempBytes.length)) != -1) {
                    baos.write(tempBytes, 0, numBytesRead);
                }

                byte[] result = baos.toByteArray();

                response.setContentLength(result.length);
                out.write(result);
            }
        } else {
            // the content isn't compressed
            // regardless if the client browser supports gzip we will just return the content
            response.setContentLength(content.length);
            out.write(content);
        }
        out.flush();
    }
    
    
    /**
     * @return true if the content is GZIP compressed
     */
    public boolean isContentGZiped() {
        return "gzip".equals(contentEncoding);
    }

}