import ca.bc.gov.devops.OpenShiftHelper
import java.nio.file.Paths
import groovy.cli.picocli.CliBuilder
import static OpenShiftHelper.ocGet

@groovy.transform.SourceURI URI scriptSourceUri

File scriptSourceFile = Paths.get(scriptSourceUri).toFile()

cli = new CliBuilder(usage: "groovy ${scriptSourceFile.getName()} --pr=<pull request#>")

cli.with {
  h(longOpt: 'help', 'Show usage information')
  c(longOpt: 'config', args: 1, argName: 'Pipeline config file', 'Pipeline config file', required: true)
  e(longOpt: 'env', args: 1, argName: 'Target environment name', 'Target environment name', required: true)
  _(longOpt: 'pr', args: 1, argName: 'Pull Request Number', 'GitHub Pull Request #', required: true)
}


opt = cli.parse(args)


if (opt == null) {
  //System.err << 'Error while parsing command-line options.\n'
  //cli.usage()
  System.exit 2
}

if (opt?.h) {
  cli.usage()
  return 0
}

def config = OpenShiftHelper.loadDeploymentConfig(opt)
def toolsNamespace = "devhub-tools"

def appLabel="${config.app.deployment.id}"
def routes = ocGet(['routes','-l', "app=${appLabel}", "--namespace=${config.app.deployment.namespace}"])

routes.items.each {Map route ->
  String routeProtocol = ((route.spec?.tls!=null)?'https':'http')
  String routeUrl = "${routeProtocol}://${route.spec.host}${route.spec.path?:'/'}"
  println "URLs found:  ${routeUrl}"
  OpenShiftHelper._exec(["bash", '-c', "oc process -f openshift/bddstack.pod.json -l 'bdd=${route.metadata.name}' -l 'app-name=${config.app.name}' -l 'app=${appLabel}' -p 'NAME=bdd-stack' -p 'URL=${routeUrl}' -p 'SUFFIX=${config.app.build.suffix}' -p 'VERSION=${config.app.build.version}' --namespace='${toolsNamespace}' |  oc replace -f - --namespace='${toolsNamespace}' --force=true"], new StringBuffer(), new StringBuffer())
}

int inprogress=1
boolean hasFailed=false;


while(inprogress>0){
  Map pods = ocGet(['pods','-l', "app=${appLabel}", "--namespace=${toolsNamespace}"])
  inprogress=0
  for (Map pod:pods.items){
    if ('Failed' == pod.status.phase) {
      hasFailed = true
      continue
    }
    if ('Succeeded' == pod.status.phase) continue
    println "Waiting for '${pod.metadata.name}' (${pod.status.phase})"
    inprogress++
  }
  Thread.sleep(2000)
}

if (hasFailed) System.exit(1)
