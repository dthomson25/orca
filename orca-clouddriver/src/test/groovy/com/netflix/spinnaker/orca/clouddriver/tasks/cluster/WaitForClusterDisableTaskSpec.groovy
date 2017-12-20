/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.cluster

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.orca.clouddriver.OortService
import com.netflix.spinnaker.orca.clouddriver.tasks.providers.aws.AmazonServerGroupCreator
import com.netflix.spinnaker.orca.clouddriver.tasks.servergroup.WaitForRequiredInstancesDownTask
import com.netflix.spinnaker.orca.clouddriver.utils.OortHelper
import com.netflix.spinnaker.orca.pipeline.model.Execution
import com.netflix.spinnaker.orca.pipeline.model.Stage
import retrofit.client.Response
import retrofit.mime.TypedByteArray
import spock.lang.Specification
import spock.lang.Subject

import static com.netflix.spinnaker.orca.ExecutionStatus.RUNNING
import static com.netflix.spinnaker.orca.ExecutionStatus.SUCCEEDED
import static retrofit.RetrofitError.httpError


class WaitForClusterDisableTaskSpec extends Specification
{

  def oortService = Mock(OortService)
  def objectMapper = new ObjectMapper()
  def collection =  new ArrayList<AmazonServerGroupCreator>()
  def waitForRequiredInstancesDownTask = new WaitForRequiredInstancesDownTask()

  @Subject def task = new WaitForClusterDisableTask(
    collection
  )

  def "does not complete if previous ASG is attached to a load balancer"() {
    given:
    task.waitForRequiredInstancesDownTask = waitForRequiredInstancesDownTask
    task.oortHelper = new OortHelper(oortService: oortService, objectMapper: objectMapper)
    def stage = new Stage(Execution.newPipeline("orca"), "test", [
      cluster               : clusterName,
      credentials           : account,
      "deploy.server.groups": [
        (region): ["$clusterName-$newServerGroup".toString()]
      ]
    ])
    stage.startTime = System.currentTimeMillis() - delay

    and:
    oortService.getCluster(*_) >> clusterResponse(
      name: clusterName,
      serverGroups: [
        [
          name  : "$clusterName-$newServerGroup".toString(),
          region: region,
          health: [
            [
              healthClass: "platform",
              state: "Unknown",
              type: "Amazon"
            ],
            [
              loadBalancers:[
                []
              ],
              state: "Up",
              type: "LoadBalancer"
            ]
          ],
          instances:[
            [
              name: "i-1234567890",
              launchTime: "1234567890",
              health: [
                [
                  healthClass: "platform",
                  state: "Unknown",
                  type: "Amazon"
                ],
                [
                  loadBalancers:[ [] ],
                  state: "Up",
                  type: "LoadBalancer"
                ]
              ],
              healthState: "Up",
              zone: "us-west-2"
            ]
          ],
          disabled: true
        ],
        [
          name  : "$clusterName-$oldServerGroup".toString(),
          region: region,
          health: [
            [
              healthClass: "platform",
              state: "Unknown",
              type: "Amazon"
            ],
            [
              loadBalancers:[ [] ],
              state: "Up",
              type: "LoadBalancer"
            ]
          ],
          instances:[
            [
              name: "i-1234567890",
              launchTime: "1234567890",
              health: [
                [
                  healthClass: "platform",
                  state: "Unknown",
                  type: "Amazon"
                ],
                [
                  loadBalancers:[ [] ],
                  state: "Up",
                  type: "LoadBalancer"
                ]
              ],
              healthState: "Up",
              zone: "us-west-2"
            ],
            []
          ],
          disabled: true
        ]
      ]
    )

    when:
    def result = task.execute(stage)

    then:
    result.status == RUNNING

    where:
    clusterName = "spindemo-test-prestaging"
    account = "test"
    region = "us-east-1"
    oldServerGroup = "v167"
    newServerGroup = "v168"
    delay = 90000
  }

  def "does complete if previous ASG is disabled and detached from the load balancer after waiting minimum time"() {
    given:
    task.waitForRequiredInstancesDownTask = waitForRequiredInstancesDownTask
    task.oortHelper = new OortHelper(oortService: oortService, objectMapper: objectMapper)
    def stage = new Stage(Execution.newPipeline("orca"), "test", [
      cluster               : clusterName,
      credentials           : account,
      "deploy.server.groups": [
        (region): ["$clusterName-$newServerGroup".toString()]
      ]
    ])
    stage.startTime = System.currentTimeMillis() - delay

    and:
    oortService.getCluster(*_) >> clusterResponse(
      name: clusterName,
      serverGroups: [
        [
          name  : "$clusterName-$newServerGroup".toString(),
          region: region,
          health: [
            [
              healthClass: "platform",
              state: "Unknown",
              type: "Amazon"
            ]
          ],
          instances:[
            [
              name: "i-1234567890",
              launchTime: "1234567890",
              health: [
                [
                  healthClass: "platform",
                  state: "Unknown",
                  type: "Amazon"
                ]
              ],
              healthState: "Up",
              zone: "us-west-2"
            ]
          ],
          disabled: true
        ],
        [
          name  : "$clusterName-$oldServerGroup".toString(),
          region: region,
          health: [
            [
              healthClass: "platform",
              state: "Unknown",
              type: "Amazon"
            ],
            [
              loadBalancers:[ [] ],
              state: "Up",
              type: "LoadBalancer"
            ]
          ],
          instances:[
            [
              name: "i-1234567890",
              launchTime: "1234567890",
              health: [
                [
                  healthClass: "platform",
                  state: "Unknown",
                  type: "Amazon"
                ],
                [
                  loadBalancers:[ [] ],
                  state: "Up",
                  type: "LoadBalancer"
                ]
              ],
              healthState: "Up",
              zone: "us-west-2"
            ],
            []
          ],
          disabled: true

        ]
      ]
    )

    when:
    def result = task.execute(stage)

    then:
    result.status == SUCCEEDED

    where:
    clusterName = "spindemo-test-prestaging"
    account = "test"
    region = "us-east-1"
    oldServerGroup = "v167"
    newServerGroup = "v168"
    //How to access default? change to public?
    delay = 90000
  }

  Response clusterResponse(body) {
    new Response("http://cloudriver", 200, "OK", [], new TypedByteArray("application/json", objectMapper.writeValueAsString(body).bytes))
  }

}
