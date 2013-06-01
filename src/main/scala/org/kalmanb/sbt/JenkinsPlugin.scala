package org.kalmanb.sbt

import sbt._
import sbt.complete.Parser
import complete.DefaultParsers._
import Keys._
import dispatch._
import scala.xml.Document
import scala.xml.transform.RewriteRule
import scala.xml.Elem

object JenkinsPlugin extends JenkinsPluginTrait
trait JenkinsPluginTrait extends Plugin {

  // Settings for Build.scala
  val jenkinsBaseUrl = SettingKey[String]("jenkinsBaseUrl", "The base URL for your Jenkins Server, eg http://jenkins.foo.com")

  // Tasks
  val jenCopyJob = InputKey[Unit]("jenkins-copy-job", "<scr> <dest> create a copy of an existing job")
  val jenBuildJob = InputKey[Unit]("jenkins-build-job", "<name> start a build for a Job")
  val jenDeleteJob = InputKey[Unit]("jenkins-delete-job", "<name> delete Job from Jenkins")
  val jenChangeJobBranch = InputKey[Unit]("jenkins-change-job-branch", "<name> <branch> change a jobs git branch setting")

  val jenCreateView = InputKey[Unit]("jenkins-create-view", "<name> create a new view")
  val jenCopyView = InputKey[Unit]("jenkins-copy-view", "<src> <dst> [prefix] creates a new view with name <dst> and duplicates all jobs in <src>. Prefix is for the new jobs, it's optional and defaults to <dst>")
  val jenAddJobToView = InputKey[Unit]("jenkins-add-job-to-view", "<job-name> <view-name> create a new view")
  val jenDeleteView = InputKey[Unit]("jenkins-delete-view", "<name> deletes the view, does NOT delete the jobs in the view")
  val jenDeleteViewAndJobs = InputKey[Unit]("jenkins-delete-view-and-jobs", "<name> deletes the view and deletes all the jobs in the view")
  val jenBuildAllJobsInView = InputKey[Unit]("jenkins-build-all-jobs-in-view", "<name> queues the build of all jobs")

  lazy val jenkinsSettings = Seq(
    jenCopyJob <<= inputTask { (argTask) ⇒
      (baseDirectory, jenkinsBaseUrl, argTask) map { (baseDirectory, host, args) ⇒
        validateArgs(args, 2); Jenkins(host).copyJob(args.head, args(1))
      }
    },
    jenBuildJob <<= inputTask { (argTask) ⇒
      (jenkinsBaseUrl, argTask) map { (host, args) ⇒
        validateArgs(args, 1); Jenkins(host).buildJob(args.head)
      }
    },
    jenDeleteJob <<= inputTask { (argTask) ⇒
      (jenkinsBaseUrl, argTask) map { (host, args) ⇒
        validateArgs(args, 1); Jenkins(host).deleteJob(args.head)
      }
    },
    jenChangeJobBranch <<= inputTask { (argTask) ⇒
      (jenkinsBaseUrl, argTask) map { (host, args) ⇒
        validateArgs(args, 2); Jenkins(host).changeJobGitBranch(args.head, args(1))
      }
    },
    jenCreateView <<= inputTask { (argTask) ⇒
      (jenkinsBaseUrl, argTask) map { (host, args) ⇒
        validateArgs(args, 1); Jenkins(host).createView(args.head)
      }
    },
    jenCopyView <<= inputTask { (argTask) ⇒
      (jenkinsBaseUrl, argTask) map { (host, args) ⇒
        validateArgs(args, 2); Jenkins(host).copyView(args.head, args(1))
      }
    },
    jenAddJobToView <<= inputTask { (argTask) ⇒
      (jenkinsBaseUrl, argTask) map { (host, args) ⇒
        validateArgs(args, 2); Jenkins(host).addJobToView(args.head, args(1))
      }
    },
    jenDeleteView <<= inputTask { (argTask) ⇒
      (jenkinsBaseUrl, argTask) map { (host, args) ⇒
        validateArgs(args, 1); Jenkins(host).deleteView(args.head)
      }
    },
    jenDeleteViewAndJobs <<= inputTask { (argTask) ⇒
      (jenkinsBaseUrl, argTask) map { (host, args) ⇒
        validateArgs(args, 1); Jenkins(host).deleteViewAndJobs(args.head)
      }
    },
    jenBuildAllJobsInView <<= inputTask { (argTask) ⇒
      (jenkinsBaseUrl, argTask) map { (host, args) ⇒
        validateArgs(args, 1); Jenkins(host).buildAllJobsInView(args.head)
      }
    }
  )
  def validateArgs(args: Seq[String], size: Int) {
    if (args.size != size) throw new IllegalArgumentException("expected %s args, got %s".format(size, args.size))
  }

  case class Jenkins(baseUrl: String) {

    def createView(view: String) {
      val params = Map("name" -> view, "mode" -> "hudson.model.ListView",
        "json" -> "{\"name\": \"%s\", \"mode\": \"hudson.model.ListView\"}".format(view))
      Http(dispatch.url(baseUrl + "/createView") << params)()
    }

    def deleteView(view: String) {
      Http(dispatch.url(baseUrl + "/view/%s/doDelete".format(view)).POST)()
    }

    def getViewConfig(view: String) =
      Http(dispatch.url(baseUrl + "/view/%s/config.xml".format(view)) OK as.xml.Elem)()

    def addJobToView(job: String, view: String) {
      Http(dispatch.url(baseUrl + "/view/%s/addJobToView".format(view)) << Map("name" -> job) OK as.String)()
    }

    def getJobConfig(job: String) =
      Http(dispatch.url(baseUrl + "/job/%s/config.xml".format(job)) OK as.xml.Elem)()

    def updateJobConfig(job: String, config: Seq[scala.xml.Node]) =
      Http(dispatch.url(baseUrl + "/job/%s/config.xml".format(job)).POST.setBody(config.mkString) OK as.String)()

    def changeJobGitBranch(job: String, newBranch: String) {
      val config = getJobConfig(job)

      val updated = new RewriteRule {
        override def transform(n: scala.xml.Node): Seq[scala.xml.Node] = n match {
          case Elem(prefix, "hudson.plugins.git.BranchSpec", attribs, scope, child @ _*) ⇒ Elem(prefix, "hudson.plugins.git.BranchSpec", attribs, scope, <name>{ newBranch }</name>: _*)
          case elem: Elem ⇒ elem copy (child = elem.child flatMap (this transform))
          case other ⇒ other
        }
      } transform config

      updateJobConfig(job, updated)
    }

    def createJob(job: String, config: Seq[scala.xml.Node]) {
      Http(dispatch.url(baseUrl + "/createItem".format(job)).POST
        .setBody(config.mkString).setHeader("Content-Type", "text/xml") <<? Map("name" -> job) OK as.String)()
    }

    def copyJob(src: String, dst: String) {
      val params = Map("name" -> dst, "mode" -> "copy", "from" -> src)
      Http(dispatch.url(baseUrl + "/createItem") << params)()
    }

    def buildJob(job: String) {
      Http(dispatch.url(baseUrl + "/job/%s/build".format(job)))()
    }

    def deleteJob(job: String) {
      Http(dispatch.url(baseUrl + "/job/%s/doDelete".format(job)).POST)()
    }

    def buildAllJobsInView(view: String) {
      getJobsInView(view).foreach(buildJob)
    }

    def getJobsInView(view: String) = {
      val config = Http(dispatch.url(baseUrl + "/view/%s/config.xml".format(view)) OK as.xml.Elem)()
      val nodes = config \\ "jobNames" \\ "string"
      nodes.map(_.text)
    }

    def copyView(src: String, dst: String) { copyView(src, dst, dst + "_") }

    def copyView(src: String, dst: String, prefix: String) {
      createView(dst)
      val originalJobs = getJobsInView(src)
      println(originalJobs)
      originalJobs.foreach(job ⇒ copyJob(job, prefix + job))
      originalJobs.foreach(job ⇒ addJobToView(prefix + job, dst))
    }

    def deleteViewAndJobs(view: String) {
      getJobsInView(view).foreach(deleteJob)
      deleteView(view)
    }
  }
}