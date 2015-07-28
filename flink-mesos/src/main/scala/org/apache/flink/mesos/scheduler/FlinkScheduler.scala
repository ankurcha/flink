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

package org.apache.flink.mesos.scheduler

import java.net.InetAddress
import java.util.{List => JList}

import com.google.protobuf.ByteString
import org.apache.flink.configuration.ConfigConstants._
import org.apache.flink.configuration.{Configuration, GlobalConfiguration}
import org.apache.flink.runtime.StreamingMode
import org.apache.flink.runtime.jobmanager.{JobManager, JobManagerMode}
import org.apache.flink.runtime.util.EnvironmentInformation
import org.apache.mesos.Protos.TaskState._
import org.apache.mesos.Protos._
import org.apache.mesos.{MesosSchedulerDriver, Scheduler, SchedulerDriver}
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val confDir = opt[String](required = true,
                            descr = "Configuration directory for flink")
  val host = opt[String](required = false,
                         default = Some(InetAddress.getLocalHost.getHostName),
                         descr = "override hostname for this jobmanager")
}

sealed case class RunningTaskManager(taskId: TaskID, slaveId: SlaveID)

object FlinkScheduler extends Scheduler with SchedulerUtils {

  val LOG = LoggerFactory.getLogger(FlinkScheduler.getClass)
  var jobManager: Option[Thread] = None
  var currentConfiguration: Option[Configuration] = None
  var taskManagers: Set[RunningTaskManager] = Set()
  var taskmanagerCount = 0

  override def offerRescinded(driver: SchedulerDriver, offerId: OfferID): Unit = {
    LOG.warn(s"Rescinded offer: ${offerId.getValue}")
  }

  override def disconnected(driver: SchedulerDriver): Unit = {
    LOG.warn("Disconnected from Mesos master.")
  }

  override def reregistered(driver: SchedulerDriver, masterInfo: MasterInfo): Unit = {
    LOG.info(s"Re-registered with master $masterInfo")
  }

  override def slaveLost(driver: SchedulerDriver, slaveId: SlaveID): Unit = {
    LOG.warn(s"Slave lost: ${slaveId.getValue}")
    // remove taskManager from that slave
    taskManagers = taskManagers.filter(_.slaveId != slaveId)
  }

  override def error(driver: SchedulerDriver, message: String): Unit = { }

  override def frameworkMessage(driver: SchedulerDriver, executorId: ExecutorID,
                                slaveId: SlaveID, data: Array[Byte]): Unit = { }

  override def registered(driver: SchedulerDriver, frameworkId: FrameworkID,
                          masterInfo: MasterInfo): Unit = {
    LOG.info(s"Registered as ${frameworkId.getValue} with master $masterInfo")
  }

  override def executorLost(driver: SchedulerDriver, executorId: ExecutorID,
                            slaveId: SlaveID, status: Int): Unit = {
    LOG.warn(s"Executor ${executorId.getValue} lost with status $status on slave $slaveId")
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
    val taskId = status.getTaskId.getValue
    val slaveId = status.getSlaveId.getValue
    LOG.info(
      s"Status update of $taskId to ${status.getState.name()} with message ${status.getMessage}")
    status.getState match {
      case TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST | TASK_ERROR =>
        LOG.info(
          s"We lost the taskManager with TaskId: $taskId on slave: $slaveId")
        // remove from taskManagers map
        taskManagers = taskManagers.filter(_.taskId != status.getTaskId)
      case _ =>
        LOG.debug(s"Ignorable TaskStatus received: ${status.getState.name()}")
    }
  }

  override def resourceOffers(driver: SchedulerDriver, offers: JList[Offer]): Unit = {
    val maxTaskManagers = GlobalConfiguration.getInteger(
      TASK_MANAGER_COUNT_KEY, DEFAULT_TASK_MANAGER_COUNT)
    val requiredMem = GlobalConfiguration.getFloat(
      TASK_MANAGER_MEM_KEY, DEFAULT_TASK_MANAGER_MEM)
    val requiredCPU = GlobalConfiguration.getFloat(
      TASK_MANAGER_CPU_KEY, DEFAULT_TASK_MANAGER_CPU.toFloat)
    val requiredDisk = GlobalConfiguration.getFloat(
      TASK_MANAGER_DISK_KEY, DEFAULT_TASK_MANGER_DISK)
    val attributeConstraints = parseConstraintString(GlobalConfiguration.getString(
      TASK_MANAGER_OFFER_ATTRIBUTES_KEY, DEFAULT_TASK_MANAGER_OFFER_ATTRIBUTES))
    val role = GlobalConfiguration.getString(
      MESOS_FRAMEWORK_ROLE_KEY, DEFAULT_MESOS_FRAMEWORK_ROLE)
    val uberJarLocation = GlobalConfiguration.getString(
      FLINK_UBERJAR_LOCATION_KEY, null)
    val nativeLibPath = GlobalConfiguration.getString(
      MESOS_NATIVE_JAVA_LIBRARY_KEY, DEFAULT_MESOS_NATIVE_JAVA_LIBRARY)

    // we will combine all resources from te same slave and then launch a single task rather
    // than one per offer this way we have better utilization and less memory wasted on overhead.
    val offersBySlaveId: Map[SlaveID, mutable.Buffer[Offer]] = offers.groupBy(_.getSlaveId)
    for ((slaveId, offers) <- offersBySlaveId) {
      val totalMemory = offers.flatMap(_.getResourcesList
        .filter(x => x.getName == "mem" && x.getRole == role)
        .map(_.getScalar.getValue)).sum
      val totalCPU = offers.flatMap(_.getResourcesList
        .filter(x => x.getName == "cpus" && x.getRole == role)
        .map(_.getScalar.getValue)).sum
      val totalDisk = offers.flatMap(_.getResourcesList
        .filter(x => x.getName == "disk" && x.getRole == role)
        .map(_.getScalar.getValue)).sum
      val portRanges = offers.flatMap(_.getResourcesList
        .filter(x => x.getName == "ports" && x.getRole == role)
        .flatMap(_.getRanges.getRangeList))
      val ports = getNPortsFromPortRanges(2, portRanges)
      val offerAttributes = toAttributeMap(offers.flatMap(_.getAttributesList))

      // check if all constraints are satisfield
      //  1. Attribute constraints
      //  2. Memory requirements
      //  3. CPU requirements
      //  4. Port requirements
      val meetsRequirements =
        taskManagers.size < maxTaskManagers &&
        totalCPU >= requiredCPU &&
        totalMemory >= requiredMem &&
        totalDisk >= requiredDisk &&
        ports.size == 2 &&
        matchesAttributeRequirements(attributeConstraints, offerAttributes)
      val portRangeDebugStr = portRanges.map( x => x.getBegin + "-" + x.getEnd).mkString(",")

      val tasksToLaunch: Seq[TaskInfo] = if (!meetsRequirements) {
        LOG.info(
          s"Declining offer(s) from slave ${slaveId.getValue} " +
          s"[cpus: $totalCPU mem : $totalMemory disk: $totalDisk ports: $portRangeDebugStr]")

        Seq()
      } else {
        LOG.info(
          s"Accepting offer(s) from slave ${slaveId.getValue} " +
          s"[cpus: $totalCPU mem : $totalMemory disk: $totalDisk ports: $portRangeDebugStr]" +
          s"required [cpus: $requiredCPU mem: $requiredMem disk: $requiredDisk]")

        // create task Id
        taskmanagerCount += 1

        val taskId = TaskID.newBuilder().setValue(s"TaskManager_$taskmanagerCount").build()
        val artifactURIs: Set[String] = Set(uberJarLocation)
        val command = createTaskManagerCommand(requiredMem.toInt)
        LOG.debug(s"Executor command: $command")

        val executorInfo = createExecutorInfo(
            s"$taskmanagerCount", role, artifactURIs, command, nativeLibPath)
        val taskInfo = createTaskInfo("taskManager", taskId, slaveId, role, requiredMem,
          requiredCPU, requiredDisk, ports, executorInfo, currentConfiguration.get)

        Seq(taskInfo)
      }

      // launch tasks for these offers (if any)
      driver.launchTasks(offers.map(_.getId), tasksToLaunch)
    }
  }

  def startJobManager(hostName: String): Try[Unit] = {
    val conf = GlobalConfiguration.getConfiguration
    val executionMode = JobManagerMode.CLUSTER
    val listeningHost = conf.getString(JOB_MANAGER_IPC_ADDRESS_KEY, hostName)
    val listeningPort = conf.getInteger(JOB_MANAGER_IPC_PORT_KEY, DEFAULT_JOB_MANAGER_IPC_PORT)
    val streamingMode =
      conf.getString(STREAMING_MODE_KEY, DEFAULT_STREAMING_MODE) match {
        case "streaming" => StreamingMode.STREAMING
        case _ => StreamingMode.BATCH_ONLY
      }

    // add jobmanager configuration
    conf.setString(JOB_MANAGER_IPC_ADDRESS_KEY, listeningHost)
    conf.setInteger(JOB_MANAGER_IPC_PORT_KEY, listeningPort)

    currentConfiguration = Some(conf)

    // start jobManager
    Try(JobManager.runJobManager(conf, executionMode, streamingMode, listeningHost, listeningPort))
  }

  def createTaskManagerCommand(mem: Int): String = {
    val tmJVMHeap = math.round(mem / (1 + JVM_MEM_OVERHEAD_PERCENT_DEFAULT))
    val tmJVMArgs = GlobalConfiguration.getString(
      TASK_MANAGER_JVM_ARGS_KEY, DEFAULT_TASK_MANAGER_JVM_ARGS)

    createJavaExecCommand(
      jvmArgs = s"$tmJVMArgs -Xmx${tmJVMHeap}m",
      classToExecute = "org.apache.flink.mesos.executor.TaskManagerExecutor")
  }

  def getNPortsFromPortRanges(n: Int, portRanges: Seq[Value.Range]): Set[Int] = {
    var ports: Set[Int] = Set()

    // find some ports
    for (x <- portRanges) {
      var begin = x.getBegin
      val end = x.getEnd

      while (begin < end && ports.size < n) {
        ports += begin.toInt
        begin += 1
      }

      if (ports.size == n) {
        return ports
      }
    }

    ports
  }

  def checkEnvironment(args: Array[String]): Unit = {
    EnvironmentInformation.logEnvironmentInfo(LOG, "JobManager", args)
    EnvironmentInformation.checkJavaVersion()
    val maxOpenFileHandles = EnvironmentInformation.getOpenFileHandlesLimit
    if (maxOpenFileHandles != -1) {
      LOG.info(s"Maximum number of open file descriptors is $maxOpenFileHandles")
    } else {
      LOG.info("Cannot determine the maximum number of open file descriptors")
    }
  }

  def main(args: Array[String]) {

    val cliConf = new Conf(args)

    // startup checks
    checkEnvironment(args)

    LOG.info(s"Loading configuration from ${cliConf.confDir()}")
    GlobalConfiguration.loadConfiguration(cliConf.confDir())
    LOG.info(s"Loaded configuration: ${GlobalConfiguration.getConfiguration.toMap}")

    val jobManagerThread: Thread = new Thread {
      override def run(): Unit = {
        // start the job
        startJobManager(cliConf.host()) match {
          case Success(_) =>
            LOG.info("JobManager finished")
            sys.exit(0)
          case Failure(throwable) =>
            LOG.error("Caught exception, committing suicide.", throwable)
            sys.exit(1)
        }
      }
    }

    // start job manager thread
    jobManager = Some(jobManagerThread)
    jobManagerThread.start()

    // register a termination hook
    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run(): Unit = {
        if (jobManager.isDefined) {
          jobManager.get.stop()
          jobManager = None
        }
      }
    })

    // start scheduler
    val frameworkBuilder = FrameworkInfo.newBuilder()

    frameworkBuilder.setUser(GlobalConfiguration.getString(
      MESOS_FRAMEWORK_USER_KEY, DEFAULT_MESOS_FRAMEWORK_USER))
    val frameworkId = GlobalConfiguration.getString(
      MESOS_FRAMEWORK_ID_KEY, DEFAULT_MESOS_FRAMEWORK_ID)
    if (frameworkId != null) {
      frameworkBuilder.setId(FrameworkID.newBuilder().setValue(frameworkId))
    }

    frameworkBuilder.setRole(GlobalConfiguration.getString(
      MESOS_FRAMEWORK_ROLE_KEY, DEFAULT_MESOS_FRAMEWORK_ROLE))
    frameworkBuilder.setName(GlobalConfiguration.getString(
      MESOS_FRAMEWORK_NAME_KEY, DEFAULT_MESOS_FRAMEWORK_NAME))
    val webUIPort = GlobalConfiguration.getInteger(JOB_MANAGER_WEB_PORT_KEY, -1)
    if (webUIPort > 0) {
      val webUIHost = GlobalConfiguration.getString(
        JOB_MANAGER_IPC_ADDRESS_KEY, cliConf.host())
      frameworkBuilder.setWebuiUrl(s"http://$webUIHost:$webUIPort")
    }

    var credsBuilder: Credential.Builder = null
    val principal = GlobalConfiguration.getString(
      MESOS_FRAMEWORK_PRINCIPAL_KEY, DEFAULT_MESOS_FRAMEWORK_PRINCIPAL)
    if (principal != null) {
      frameworkBuilder.setPrincipal(principal)

      credsBuilder = Credential.newBuilder()
      credsBuilder.setPrincipal(principal)
      val secret = GlobalConfiguration.getString(
        MESOS_FRAMEWORK_SECRET_KEY, DEFAULT_MESOS_FRAMEWORK_SECRET)
      if (secret != null) {
        credsBuilder.setSecret(ByteString.copyFromUtf8(secret))
      }
    }

    val master = GlobalConfiguration.getString(MESOS_MASTER_KEY, DEFAULT_MESOS_MASTER)

    val driver =
      if (credsBuilder != null) {
        new MesosSchedulerDriver(this, frameworkBuilder.build, master, credsBuilder.build)
      } else {
        new MesosSchedulerDriver(this, frameworkBuilder.build, master)
      }

    val status = if (driver.run eq Status.DRIVER_STOPPED) 0 else 1
    sys.exit(status)
  }
}