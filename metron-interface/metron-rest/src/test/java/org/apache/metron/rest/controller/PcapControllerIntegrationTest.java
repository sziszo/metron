/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.metron.rest.controller;

import org.adrianwalker.multilinestring.Multiline;
import org.apache.hadoop.fs.Path;
import org.apache.metron.common.Constants;
import org.apache.metron.common.utils.JSONUtils;
import org.apache.metron.job.JobStatus;
import org.apache.metron.job.Pageable;
import org.apache.metron.pcap.PcapHelper;
import org.apache.metron.pcap.PcapPages;
import org.apache.metron.pcap.filter.fixed.FixedPcapFilter;
import org.apache.metron.rest.mock.MockPcapJob;
import org.apache.metron.rest.model.PcapResponse;
import org.apache.metron.rest.service.PcapService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.apache.metron.rest.MetronRestConstants.TEST_PROFILE;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(TEST_PROFILE)
public class PcapControllerIntegrationTest {

  /**
   {
   "basePath": "/base/path",
   "baseInterimResultPath": "/base/interim/result/path",
   "finalOutputPath": "/final/output/path",
   "startTimeMs": 10,
   "endTimeMs": 20,
   "numReducers": 2,
   "includeReverse": "true",
   "ipDstAddr": "192.168.1.1",
   "ipDstPort": "1000",
   "ipSrcAddr": "192.168.1.2",
   "ipSrcPort": "2000",
   "packetFilter": "filter",
   "protocol": "TCP"
   }
   */
  @Multiline
  public static String fixedJson;

  /**
   {
   "includeReverse": "true",
   "ipDstAddr": "192.168.1.1",
   "ipDstPort": "1000",
   "ipSrcAddr": "192.168.1.2",
   "ipSrcPort": "2000",
   "packetFilter": "filter",
   "protocol": "TCP"
   }
   */
  @Multiline
  public static String fixedWithDefaultsJson;

  @Autowired
  private PcapService pcapService;

  @Autowired
  private WebApplicationContext wac;

  private MockMvc mockMvc;

  private String pcapUrl = "/api/v1/pcap";
  private String user = "user";
  private String user2 = "user2";
  private String password = "password";

  @Before
  public void setup() throws Exception {
    this.mockMvc = MockMvcBuilders.webAppContextSetup(this.wac).apply(springSecurity()).build();
  }

  @Test
  public void testSecurity() throws Exception {
    this.mockMvc.perform(post(pcapUrl + "/fixed").with(csrf()).contentType(MediaType.parseMediaType("application/json;charset=UTF-8")).content(fixedJson))
            .andExpect(status().isUnauthorized());
  }

  @Test
  public void testFixedRequest() throws Exception {
    MockPcapJob mockPcapJob = (MockPcapJob) wac.getBean("mockPcapJob");
    List<byte[]> results = Arrays.asList("pcap1".getBytes(), "pcap2".getBytes());
    mockPcapJob.setResults(results);
    mockPcapJob.setStatus(new JobStatus().withState(JobStatus.State.RUNNING));

    PcapResponse expectedReponse = new PcapResponse();
    expectedReponse.setPcaps(results);
    this.mockMvc.perform(post(pcapUrl + "/fixed").with(httpBasic(user, password)).with(csrf()).contentType(MediaType.parseMediaType("application/json;charset=UTF-8")).content(fixedJson))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/json;charset=UTF-8")))
            .andExpect(jsonPath("$.jobStatus").value("RUNNING"));

    Assert.assertEquals("/base/path", mockPcapJob.getBasePath());
    Assert.assertEquals("/base/interim/result/path", mockPcapJob.getBaseInterrimResultPath());
    Assert.assertEquals("/final/output/path", mockPcapJob.getFinalOutputPath());
    Assert.assertEquals(10000000, mockPcapJob.getStartTimeNs());
    Assert.assertEquals(20000000, mockPcapJob.getEndTimeNs());
    Assert.assertEquals(2, mockPcapJob.getNumReducers());
    Assert.assertTrue(mockPcapJob.getFilterImpl() instanceof FixedPcapFilter.Configurator);
    Map<String, String> actualFixedFields = mockPcapJob.getFixedFields();
    Assert.assertEquals("192.168.1.2", actualFixedFields.get(Constants.Fields.SRC_ADDR.getName()));
    Assert.assertEquals("2000", actualFixedFields.get(Constants.Fields.SRC_PORT.getName()));
    Assert.assertEquals("192.168.1.1", actualFixedFields.get(Constants.Fields.DST_ADDR.getName()));
    Assert.assertEquals("1000", actualFixedFields.get(Constants.Fields.DST_PORT.getName()));
    Assert.assertEquals("true", actualFixedFields.get(Constants.Fields.INCLUDES_REVERSE_TRAFFIC.getName()));
    Assert.assertEquals("TCP", actualFixedFields.get(Constants.Fields.PROTOCOL.getName()));
    Assert.assertEquals("filter", actualFixedFields.get(PcapHelper.PacketFields.PACKET_FILTER.getName()));
  }


  @Test
  public void testFixedRequestDefaults() throws Exception {
    MockPcapJob mockPcapJob = (MockPcapJob) wac.getBean("mockPcapJob");
    mockPcapJob.setStatus(new JobStatus().withState(JobStatus.State.RUNNING));
    long beforeJobTime = System.currentTimeMillis();

    this.mockMvc.perform(post(pcapUrl + "/fixed").with(httpBasic(user, password)).with(csrf()).contentType(MediaType.parseMediaType("application/json;charset=UTF-8")).content(fixedWithDefaultsJson))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/json;charset=UTF-8")))
            .andExpect(jsonPath("$.jobStatus").value("RUNNING"));

    Assert.assertEquals("/apps/metron/pcap/input", mockPcapJob.getBasePath());
    Assert.assertEquals("/apps/metron/pcap/interim", mockPcapJob.getBaseInterrimResultPath());
    Assert.assertEquals("/apps/metron/pcap/output", mockPcapJob.getFinalOutputPath());
    Assert.assertEquals(0, mockPcapJob.getStartTimeNs());
    Assert.assertTrue(beforeJobTime < mockPcapJob.getEndTimeNs() / 1000000);
    Assert.assertTrue(System.currentTimeMillis() > mockPcapJob.getEndTimeNs() / 1000000);
    Assert.assertEquals(10, mockPcapJob.getNumReducers());
    Assert.assertTrue(mockPcapJob.getFilterImpl() instanceof FixedPcapFilter.Configurator);
    Map<String, String> actualFixedFields = mockPcapJob.getFixedFields();
    Assert.assertEquals("192.168.1.2", actualFixedFields.get(Constants.Fields.SRC_ADDR.getName()));
    Assert.assertEquals("2000", actualFixedFields.get(Constants.Fields.SRC_PORT.getName()));
    Assert.assertEquals("192.168.1.1", actualFixedFields.get(Constants.Fields.DST_ADDR.getName()));
    Assert.assertEquals("1000", actualFixedFields.get(Constants.Fields.DST_PORT.getName()));
    Assert.assertEquals("true", actualFixedFields.get(Constants.Fields.INCLUDES_REVERSE_TRAFFIC.getName()));
    Assert.assertEquals("TCP", actualFixedFields.get(Constants.Fields.PROTOCOL.getName()));
    Assert.assertEquals("filter", actualFixedFields.get(PcapHelper.PacketFields.PACKET_FILTER.getName()));
  }

  @Test
  public void testGetStatus() throws Exception {
    MockPcapJob mockPcapJob = (MockPcapJob) wac.getBean("mockPcapJob");

    this.mockMvc.perform(get(pcapUrl + "/jobId").with(httpBasic(user, password)))
            .andExpect(status().isNotFound());

    mockPcapJob.setStatus(new JobStatus().withJobId("jobId").withState(JobStatus.State.RUNNING));

    this.mockMvc.perform(post(pcapUrl + "/fixed").with(httpBasic(user, password)).with(csrf()).contentType(MediaType.parseMediaType("application/json;charset=UTF-8")).content(fixedJson))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/json;charset=UTF-8")))
            .andExpect(jsonPath("$.jobId").value("jobId"))
            .andExpect(jsonPath("$.jobStatus").value("RUNNING"));

    mockPcapJob.setStatus(new JobStatus().withJobId("jobId").withState(JobStatus.State.SUCCEEDED));

    Pageable<Path> pageable = new PcapPages(Arrays.asList(new Path("path1"), new Path("path1")));
    mockPcapJob.setIsDone(true);
    mockPcapJob.setPageable(pageable);

    this.mockMvc.perform(get(pcapUrl + "/jobId").with(httpBasic(user, password)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/json;charset=UTF-8")))
            .andExpect(jsonPath("$.jobStatus").value("SUCCEEDED"))
            .andExpect(jsonPath("$.pageTotal").value(2));

    mockPcapJob.setStatus(new JobStatus().withJobId("jobId").withState(JobStatus.State.FINALIZING));

    this.mockMvc.perform(get(pcapUrl + "/jobId").with(httpBasic(user, password)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/json;charset=UTF-8")))
            .andExpect(jsonPath("$.jobStatus").value("FINALIZING"));

    mockPcapJob.setStatus(new JobStatus().withJobId("jobId").withState(JobStatus.State.FAILED));

    this.mockMvc.perform(get(pcapUrl + "/jobId").with(httpBasic(user, password)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/json;charset=UTF-8")))
            .andExpect(jsonPath("$.jobStatus").value("FAILED"));

    mockPcapJob.setStatus(new JobStatus().withJobId("jobId").withState(JobStatus.State.KILLED));

    this.mockMvc.perform(get(pcapUrl + "/jobId").with(httpBasic(user, password)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/json;charset=UTF-8")))
            .andExpect(jsonPath("$.jobStatus").value("KILLED"));
  }
}
