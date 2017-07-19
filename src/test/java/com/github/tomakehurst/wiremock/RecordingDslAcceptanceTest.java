/*
 * Copyright (C) 2011 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.tomakehurst.wiremock;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.InvalidRequestException;
import com.github.tomakehurst.wiremock.matching.EqualToJsonPattern;
import com.github.tomakehurst.wiremock.recording.NotRecordingException;
import com.github.tomakehurst.wiremock.recording.RecordingStatus;
import com.github.tomakehurst.wiremock.recording.RecordingStatusResult;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.testsupport.WireMockTestClient;
import org.apache.http.HttpEntity;
import org.apache.http.client.entity.GzipCompressingEntity;
import org.apache.http.entity.StringEntity;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.testsupport.TestHttpHeader.withHeader;
import static com.github.tomakehurst.wiremock.testsupport.WireMatchers.findMappingWithUrl;
import static org.apache.http.entity.ContentType.TEXT_PLAIN;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class RecordingDslAcceptanceTest extends AcceptanceTestBase {

    private WireMockServer targetService;
    private WireMockServer proxyingService;
    private WireMockTestClient client;
    private WireMock adminClient;
    private String targetBaseUrl;

    public void init() {
        proxyingService = new WireMockServer(wireMockConfig()
            .dynamicPort()
            .withRootDirectory(setupTempFileRoot().getAbsolutePath()));
        proxyingService.start();

        targetService = wireMockServer;
        targetBaseUrl = "http://localhost:" + targetService.port();

        client = new WireMockTestClient(proxyingService.port());
        WireMock.configureFor(proxyingService.port());
        adminClient = new WireMock(proxyingService.port());
    }

    @After
    public void proxyServerShutdown() {
        proxyingService.resetMappings();
        proxyingService.stop();
    }

    @Test
    public void startsRecordingWithDefaultSpecFromTheSpecifiedProxyBaseUrlWhenServeEventsAlreadyExist() {
        targetService.stubFor(get("/record-this").willReturn(okForContentType("text/plain","Got it")));
        targetService.stubFor(get(urlPathMatching("/do-not-record-this/.*")).willReturn(noContent()));

        client.get("/do-not-record-this/1");
        client.get("/do-not-record-this/2");
        client.get("/do-not-record-this/3");

        startRecording(targetBaseUrl);

        client.get("/record-this");

        List<StubMapping> returnedMappings = stopRecording().getStubMappings();
        client.get("/do-not-record-this/4");


        assertThat(returnedMappings.size(), is(1));
        assertThat(returnedMappings.get(0).getRequest().getUrl(), is("/record-this"));

        StubMapping mapping = findMappingWithUrl(proxyingService.getStubMappings(), "/record-this");
        assertThat(mapping.getResponse().getBody(), is("Got it"));
    }

    @Test
    public void generatesStubNameFromUrlPath() {
        targetService.stubFor(get(urlPathMatching("/record-this/.*"))
            .willReturn(ok("Fine")));

        startRecording(targetBaseUrl);

        String url = "/record-this/with$!/safe/ŃaMe?ignore=this";
        client.get(url);

        List<StubMapping> mappings = stopRecording().getStubMappings();

        StubMapping mapping = mappings.get(0);
        assertThat(mapping.getName(), is("record-this_with_safe_name"));
    }

    @Test
    public void startsRecordingWithDefaultSpecFromTheSpecifiedProxyBaseUrlWhenNoServeEventsAlreadyExist() {
        targetService.stubFor(get("/record-this").willReturn(okForContentType("text/plain","Got it")));

        startRecording(targetBaseUrl);

        client.get("/record-this");

        List<StubMapping> returnedMappings = stopRecording().getStubMappings();

        assertThat(returnedMappings.size(), is(1));
        assertThat(returnedMappings.get(0).getRequest().getUrl(), is("/record-this"));

        StubMapping mapping = findMappingWithUrl(proxyingService.getStubMappings(), "/record-this");
        assertThat(mapping.getResponse().getBody(), is("Got it"));
    }

    @Test
    public void recordsNothingWhenNoServeEventsAreRecievedDuringRecording() {
        targetService.stubFor(get(urlPathMatching("/do-not-record-this/.*")).willReturn(noContent()));

        client.get("/do-not-record-this/1");
        client.get("/do-not-record-this/2");

        startRecording(targetBaseUrl);
        List<StubMapping> returnedMappings = stopRecording().getStubMappings();
        client.get("/do-not-record-this/3");

        assertThat(returnedMappings.size(), is(0));
        assertThat(proxyingService.getStubMappings(), Matchers.<StubMapping>empty());
    }

    @Test
    public void recordsNothingWhenNoServeEventsAreRecievedAtAll() {
        startRecording(targetBaseUrl);
        List<StubMapping> returnedMappings = stopRecording().getStubMappings();

        assertThat(returnedMappings.size(), is(0));
        assertThat(proxyingService.getStubMappings(), Matchers.<StubMapping>empty());
    }

    @Test
    public void honoursRecordSpecWhenPresent() {
        targetService.stubFor(get("/record-this-with-header").willReturn(ok()));

        startRecording(recordSpec()
            .forTarget(targetBaseUrl)
            .captureHeader("Accept")
        );

        client.get("/record-this", withHeader("Accept", "text/plain"));

        List<StubMapping> returnedMappings = stopRecording().getStubMappings();

        assertThat(returnedMappings.get(0).getRequest().getHeaders().get("Accept").getExpected(), is("text/plain"));
    }

    @Test
    public void supportsInstanceClientWithDefaultSpec() {
        targetService.stubFor(get("/record-this").willReturn(okForContentType("text/plain","Got it")));

        adminClient.startStubRecording(targetBaseUrl);

        client.get("/record-this");

        List<StubMapping> returnedMappings = adminClient.stopStubRecording().getStubMappings();

        assertThat(returnedMappings.size(), is(1));
        assertThat(returnedMappings.get(0).getRequest().getUrl(), is("/record-this"));

        StubMapping mapping = findMappingWithUrl(proxyingService.getStubMappings(), "/record-this");
        assertThat(mapping.getResponse().getBody(), is("Got it"));
    }

    @Test
    public void supportsInstanceClientWithSpec() {
        targetService.stubFor(post("/record-this-with-body").willReturn(ok()));

        adminClient.startStubRecording(
            recordSpec()
                .forTarget(targetBaseUrl)
                .jsonBodyMatchFlags(true, true)
        );

        client.postJson("/record-this-with-body", "{}");

        List<StubMapping> returnedMappings = adminClient.stopStubRecording().getStubMappings();

        EqualToJsonPattern bodyPattern = (EqualToJsonPattern) returnedMappings.get(0).getRequest().getBodyPatterns().get(0);
        assertThat(bodyPattern.isIgnoreArrayOrder(), is(true));
        assertThat(bodyPattern.isIgnoreExtraElements(), is(true));
    }

    @Test
    public void supportsDirectDslCallsWithSpec() {
        targetService.stubFor(post("/record-this-with-body").willReturn(ok()));

        proxyingService.startRecording(
            recordSpec()
                .forTarget(targetBaseUrl)
                .jsonBodyMatchFlags(true, true)
        );

        client.postJson("/record-this-with-body", "{}");

        List<StubMapping> returnedMappings = proxyingService.stopRecording().getStubMappings();

        EqualToJsonPattern bodyPattern = (EqualToJsonPattern) returnedMappings.get(0).getRequest().getBodyPatterns().get(0);
        assertThat(bodyPattern.isIgnoreArrayOrder(), is(true));
        assertThat(bodyPattern.isIgnoreExtraElements(), is(true));
    }

    @Test
    public void returnsTheRecordingStatus() {
        proxyingService.startRecording(targetBaseUrl);

        RecordingStatusResult result = getRecordingStatus();

        assertThat(result.getStatus(), is(RecordingStatus.Recording));
    }

    @Test
    public void returnsTheRecordingStatusViaInstanceClient() {
        proxyingService.startRecording(targetBaseUrl);
        proxyingService.stopRecording();

        RecordingStatusResult result = adminClient.getStubRecordingStatus();

        assertThat(result.getStatus(), is(RecordingStatus.Stopped));
    }

    @Test
    public void returnsTheRecordingStatusViaDirectDsl() {
        proxyingService.startRecording(targetBaseUrl);

        RecordingStatusResult result = proxyingService.getRecordingStatus();

        assertThat(result.getStatus(), is(RecordingStatus.Recording));
    }

    @Test
    public void recordsIntoPlainTextWhenRequestIsGZipped() {
        proxyingService.startRecording(targetBaseUrl);
        targetService.stubFor(post("/gzipped").willReturn(ok("Zippy")));

        HttpEntity compressedBody = new GzipCompressingEntity(new StringEntity("expected body", TEXT_PLAIN));
        client.post("/gzipped", compressedBody);

        StubMapping mapping = proxyingService.stopRecording().getStubMappings().get(0);
        assertThat(mapping.getRequest().getBodyPatterns().get(0).getExpected(), is("expected body"));
    }

    @Test(expected = NotRecordingException.class)
    public void throwsAnErrorIfAttemptingToStopViaStaticRemoteDslWhenNotRecording() {
        stopRecording();
    }

    @Test(expected = NotRecordingException.class)
    public void throwsAnErrorIfAttemptingToStopViaInstanceRemoteDslWhenNotRecording() {
        adminClient.stopStubRecording();
    }

    @Test(expected = NotRecordingException.class)
    public void throwsAnErrorIfAttemptingToStopViaDirectDslWhenNotRecording() {
        proxyingService.stopRecording();
    }

    @Test(expected = InvalidRequestException.class)
    public void throwsValidationErrorWhenAttemptingToStartRecordingViaStaticDslWithNoTargetUrl() {
        startRecording(recordSpec());
    }

    @Test(expected = InvalidRequestException.class)
    public void throwsValidationErrorWhenAttemptingToStartRecordingViaDirectDslWithNoTargetUrl() {
        proxyingService.startRecording(recordSpec());
    }
}