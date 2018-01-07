package de.ofahrt.catfish;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import de.ofahrt.catfish.utils.Enumerations;
import de.ofahrt.catfish.utils.HttpFieldName;
import de.ofahrt.catfish.utils.ServletHelper;

public final class ResponseImpl implements HttpServletResponse, ReadableHttpResponse {

private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");
private static final String CRLF = "\r\n";

private boolean isCommitted = false;
private boolean isCompleted = false;

private boolean isHeadRequest = false;

private int majorVersion = 0;
private int minorVersion = 9;
private String charset = "UTF-8";
private int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
private Locale defaultLocale = Locale.US;
private Locale locale;

private byte[] bodyData = null;
private int contentLength = -1;

private boolean compressable = false;

private OutputStream internalStream = null;
private OutputStream keepStream = null;
private Writer keepWriter = null;

private HashMap<String,String> header = new HashMap<String,String>();

ResponseImpl() {
}

void setHeadRequest() {
	if (isHeadRequest) throw new IllegalStateException();
	isHeadRequest = true;
}

public void setVersion(int majorVersion, int minorVersion) {
	if (isCommitted) throw new IllegalStateException();
	this.majorVersion = majorVersion;
	this.minorVersion = minorVersion;
}

@Override
public String getProtocol()
{ return "HTTP/"+majorVersion+"."+minorVersion; }

@Override
public int getStatusCode()
{ return status; }

private String canonicalize(String key)
{ return HttpFieldName.canonicalize(key); }

private void setHeaderInternal(String key, String value) {
	if (isCompleted) throw new IllegalStateException();
	header.put(canonicalize(key), value);
}

@Override
public String getHeader(String key)
{ return header.get(canonicalize(key)); }

@Override
public Enumeration<String> getHeaderNames() {
	return Enumerations.of(header.keySet());
}

public Iterator<String> getHeaderKeyIterator()
{ return header.keySet().iterator(); }

boolean isKeepAlive()
{ return "keep-alive".equals(getHeader(HttpFieldName.CONNECTION)); }

void setCompressionAllowed(boolean how)
{
	if (isCommitted) throw new IllegalStateException();
	compressable = how;
}

void disableCompression()
{ setCompressionAllowed(false); }

void enableCompression()
{ setCompressionAllowed(true); }

void setCookie(String cookie)
{
	if (isCommitted) throw new IllegalStateException();
	setHeader(HttpFieldName.SET_COOKIE, cookie);
}

private OutputStream internalOutputStream(boolean shouldCompress) throws IOException
{
	internalStream = new ByteArrayOutputStream(5000)
  	{
  		@Override
  		public void close() throws IOException
  		{
  			if (internalStream != this) throw new IOException("Cannot close stream twice!");
  			internalStream = null;
  			keepStream = null;
  			bodyData = toByteArray();
  		}
  	};
  bodyData = null;
  
  if (shouldCompress)
  {
  	setHeaderInternal(HttpFieldName.CONTENT_ENCODING, "gzip");
    keepStream = new GZIPOutputStream(internalStream);
    return keepStream;
  }
  else
  {
    keepStream = internalStream;
    return keepStream;
  }
}

private Writer internalWriter()
{
	keepWriter = new StringWriter(5000)
  	{
  		@Override
  		public void close() throws IOException
  		{
  			if (keepWriter != this) throw new IOException("Cannot close stream twice!");
  			keepWriter = null;
  			OutputStream out = internalOutputStream(compressable);
			  OutputStreamWriter writer = new OutputStreamWriter(out, charset);
			  writer.write(toString());
			  writer.flush();
			  writer.close();
  		}
  	};
  return keepWriter;
}

void setBodyString(String s)
{
	bodyData = s.getBytes();
}

void commit()
{
	isCommitted = true;
}

void close()
{
	if (isCompleted) return;
	if (!isCommitted) commit();
	try
	{
		if (keepWriter != null)
			keepWriter.close();
		if (keepStream != null)
			keepStream.close();
	}
	catch (IOException e) // cannot happen
	{ throw new RuntimeException(e); }
	
	if (bodyData != null)
		setHeaderInternal(HttpFieldName.CONTENT_LENGTH, Integer.toString(bodyData.length));
	else if (contentLength != -1)
		setHeaderInternal(HttpFieldName.CONTENT_LENGTH, Integer.toString(contentLength));
	else
		setHeaderInternal(HttpFieldName.CONTENT_LENGTH, "0");
	// The servlet specification requires writing out the content-language.
	// But locale.toString is clearly not correct.
//	if ((locale != null) && containsHeader(HttpFieldName.CONTENT_TYPE))
//		setHeader(HttpFieldName.CONTENT_LANGUAGE, locale.toString());
	isCompleted = true;
}

public InputStream getInputStream() {
	if (!isHeadRequest && (bodyData != null)) {
		return new ByteArrayInputStream(bodyData);
	} else {
		return new ByteArrayInputStream(new byte[0]);
	}
}

byte[] getHeaders() {
  if (!isCommitted) {
    commit();
  }
  if (!isCompleted) {
    close();
  }
  if ((keepWriter != null) || (keepStream != null)) {
    throw new IllegalStateException();
  }
  StringBuilder buffer = new StringBuilder(200);

  buffer.append("HTTP/");
  buffer.append(majorVersion).append(".").append(minorVersion);
  buffer.append(" ");
  buffer.append(CoreHelper.getStatusText(status));
  buffer.append(CRLF);

  Iterator<Map.Entry<String,String>> it = header.entrySet().iterator();
  while (it.hasNext()) {
    Map.Entry<String,String> entry = it.next();
    buffer.append(entry.getKey());
    buffer.append(": ");
    buffer.append(entry.getValue());
    buffer.append(CRLF);
  }
  buffer.append(CRLF);
  return buffer.toString().getBytes(DEFAULT_CHARSET);
}

byte[] getBody() {
  return isHeadRequest ? null : bodyData;
}

// ServletResponse API Implementation
@Override
public void flushBuffer()
{ throw new UnsupportedOperationException(); }

@Override
public int getBufferSize()
{ throw new UnsupportedOperationException(); }

@Override
public String getCharacterEncoding()
{ return charset; }

@Override
public String getContentType()
{ return getHeader(HttpFieldName.CONTENT_TYPE); }

@Override
public Locale getLocale()
{ return locale != null ? locale : defaultLocale; }

@Override
public ServletOutputStream getOutputStream() throws IOException
{
	final OutputStream out = internalOutputStream(compressable);
	return new ServletOutputStream()
		{
			@Override
			public void write(int b) throws IOException
			{ out.write(b); }
			@Override
			public void write(byte[] b, int off, int len) throws IOException
			{ out.write(b, off, len); }
			@Override
			public void flush()
			{ commit(); }
		};
}

@Override
public PrintWriter getWriter()
{ return new PrintWriter(internalWriter()); }

@Override
public boolean isCommitted()
{ throw new UnsupportedOperationException(); }

@Override
public void reset()
{ throw new UnsupportedOperationException(); }

@Override
public void resetBuffer()
{ throw new UnsupportedOperationException(); }

@Override
public void setBufferSize(int size)
{ throw new UnsupportedOperationException(); }

@Override
public void setCharacterEncoding(String charset)
{
	if (isCommitted) return;
	this.charset = charset;
}

@Override
public void setContentLength(int len)
{
	if (isHeadRequest)
	{
		contentLength = len;
		return;
	}
	if (isCommitted) throw new IllegalStateException();
	contentLength = len;
}

@Override
public void setContentType(String type)
{
	if (isCommitted) {
	  return;
	}
	if (type == null) {
	  throw new NullPointerException();
	}
  if (!CoreHelper.shouldCompress(ServletHelper.getMimeTypeFromContentType(type))) {
    disableCompression();
  }
  if (CoreHelper.isTextMimeType(type) && (charset != null)) {
    type += "; charset="+charset;
  }
  setHeader(HttpFieldName.CONTENT_TYPE, type);
}

@Override
public void setLocale(Locale locale) {
	if (isCommitted) return;
	this.locale = locale;
}



// HttpServletResponse API Implementation
@Override
public void addCookie(Cookie cookie)
{
	if (isCommitted) throw new IllegalStateException();
	throw new UnsupportedOperationException();
}

@Override
public void addDateHeader(String name, long date)
{
	if (isCommitted) throw new IllegalStateException();
	addHeader(canonicalize(name), CoreHelper.formatDate(date));
}

@Override
public void addHeader(String name, String value)
{
	if (isCommitted) throw new IllegalStateException();
	throw new UnsupportedOperationException();
}

@Override
public void addIntHeader(String name, int value)
{
	if (isCommitted) throw new IllegalStateException();
	addHeader(name, Integer.toString(value));
}

@Override
public boolean containsHeader(String name)
{ return header.containsKey(canonicalize(name)); }

@Override
@Deprecated
public String encodeRedirectUrl(String url)
{ throw new UnsupportedOperationException(); }

@Override
public String encodeRedirectURL(String url)
{ throw new UnsupportedOperationException(); }

@Override
@Deprecated
public String encodeUrl(String url)
{ throw new UnsupportedOperationException(); }

@Override
public String encodeURL(String url)
{ throw new UnsupportedOperationException(); }

@Override
public void sendError(int statusCode)
{ sendError(statusCode, null); }

@Override
public void sendError(int statusCode, String msg)
{
	if (isCommitted) throw new IllegalStateException();
	setStatus(statusCode);
	if (msg == null) msg = CoreHelper.getStatusText(statusCode);
	setContentType(CoreHelper.MIME_TEXT_PLAIN);
	setBodyString(msg);
	commit();
}

@Override
public void sendRedirect(String location) throws IOException
{
	if (isCommitted) throw new IllegalStateException();
	setStatus(HttpServletResponse.SC_FOUND);
	setHeader(HttpFieldName.LOCATION, location);
	setContentType(CoreHelper.MIME_TEXT_HTML);
	setBodyString("<html><head><meta http-equiv=\"refresh\" content=\"1; URL="+location+"\"></head><body>REDIRECT</body></html>");
	commit();
}

@Override
public void setDateHeader(String name, long date)
{
	if (isCommitted) throw new IllegalStateException();
	setHeaderInternal(name, CoreHelper.formatDate(date));
}

@Override
public void setHeader(String name, String value)
{
	if (isCommitted) throw new IllegalStateException();
	setHeaderInternal(name, value);
}

@Override
public void setIntHeader(String name, int value)
{
	if (isCommitted) throw new IllegalStateException();
	setHeaderInternal(name, Integer.toString(value));
}

@Override
public void setStatus(int status)
{
	if (isCommitted) throw new IllegalStateException();
	if ((status < 100) || (status > 999))
		throw new IllegalArgumentException("The status must be a positive three-digit number");
	this.status = status;
}

@Override
@Deprecated
public void setStatus(int status, String sm)
{
	if (isCommitted) throw new IllegalStateException();
	throw new UnsupportedOperationException();
}

}
