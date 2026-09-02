package com.familyfinance.identity;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(-99) // Runs after Spring Security's CSRF filter (-100) and after request correlation.
public class RegistrationRequestBodyLimitFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 4_096;

    private final ObjectMapper objectMapper;

    RegistrationRequestBodyLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            writeValidationError(response);
            return;
        }
        filterChain.doFilter(new LimitedBodyRequest(request), response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/api/auth/register".equals(request.getRequestURI());
    }

    private void writeValidationError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiEnvelope.error(
                new ApiError("VALIDATION_ERROR", "请检查输入内容", Map.of("request", "请求内容过大"))));
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private ServletInputStream limitedInputStream;

        private LimitedBodyRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limitedInputStream == null) {
                limitedInputStream = new LimitedServletInputStream(super.getInputStream(), MAX_BODY_BYTES);
            }
            return limitedInputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final int maximumBytes;
        private int consumedBytes;

        private LimitedServletInputStream(ServletInputStream delegate, int maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            if (consumedBytes == maximumBytes) {
                return readPastLimit();
            }
            int value = delegate.read();
            if (value != -1) {
                consumedBytes++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (consumedBytes == maximumBytes) {
                return readPastLimit();
            }
            int permittedLength = Math.min(length, maximumBytes - consumedBytes);
            int read = delegate.read(buffer, offset, permittedLength);
            if (read > 0) {
                consumedBytes += read;
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        private int readPastLimit() throws IOException {
            if (delegate.read() == -1) {
                return -1;
            }
            throw new RegistrationRequestBodyTooLargeException();
        }
    }
}
