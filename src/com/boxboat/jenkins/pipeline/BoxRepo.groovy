package com.boxboat.jenkins.pipeline

import com.boxboat.jenkins.library.ServerConfig
import com.boxboat.jenkins.library.Utils
import com.boxboat.jenkins.library.docker.Compose
import com.boxboat.jenkins.library.docker.Image
import com.boxboat.jenkins.library.docker.Registry

class BoxRepo extends BoxBase {

    public Map<String, String> composeProfiles = [:]
    public List<String> pullImages = []
    public List<String> pushImages = []

    BoxRepo(Map config) {
        super(config)
        config?.each { k, v -> this[k] = v }
    }

    static def createBoxRepo(Map config) {
        def repo = new BoxRepo(config)
        return repo
    }

    def init() {
        super.init()

        // pull images
        pullImages.each { image ->
            steps.sh """
                export REGISTRY="${ServerConfig.registryMap.get("dtr").host}"
                docker pull "${image}"
            """
        }
    }

    def composeBuild(String profile) {
        steps.sh Compose.build(composeProfiles.get(profile), profile, ServerConfig.registryMap.get("dtr").host)
    }

    def composeUp(String profile) {
        // clean up all profiles
        cleanup()
        // start the specified profile
        steps.sh Compose.up(composeProfiles.get(profile), profile, ServerConfig.registryMap.get("dtr").host)
    }

    def composeDown(String profile) {
        steps.sh Compose.down(composeProfiles.get(profile), profile)
    }

    def push() {
        def branch = gitRepo.branch?.toLowerCase()
        steps.echo branch
        def hash = gitRepo.shortHash

        if (branch == "dev" || branch == "master" || branch?.startsWith("feature")) {
            def isBranchTip = gitRepo.isBranchTip()
            def event = Utils.cleanTag("commit-${branch}")
            def tags = [hash]
            if (isBranchTip) {
                tags.add(event)
            }
            Registry registry = ServerConfig.registryMap.get("dtr")
            List<Image> images = pushImages.collect { String v -> Image.fromImageString(v) }
            steps.docker.withRegistry(
                    registry.getRegistryUrl(),
                    registry.credentials) {
                images.each { Image image ->
                    tags.each { String tag ->
                        def newImage = image.copy()
                        newImage.host = registry.host
                        newImage.tag = tag
                        image.reTag(steps, newImage)
                        newImage.push(steps)
                    }
                }
            }

            if (isBranchTip) {
                def dir = "build-versions/${event}"
                def script = """
                    mkdir -p "${dir}"
                """
                images.each { Image image ->
                    script += """
                        echo 'image_tag_${Utils.alphaNumericUnderscoreLower(image.path)}: "${hash}"' \\
                            > "${dir}/${Utils.alphaNumericDashLower(image.path)}.yaml"
                    """
                }

                def buildVersions = gitAccount.checkoutRepository(ServerConfig.buildVersionsGitRemoteUrl, "build-versions", 1)
                steps.sh script
                buildVersions.commitAndPush("update build-versions")
            }

            steps.echo """
                Promote Build
            """

            steps.echo 'To Promote this image, use the following link:\n' +
                    steps.env.JENKINS_URL +
                    '/job/DevOps/job/build-promotion/job/master/parambuild/?images=' +
                    URLEncoder.encode(pushImages.join('\n'), "UTF-8") +
                    '&existingTag=' +
                    URLEncoder.encode(hash, "UTF-8")
        }

    }

    def cleanup() {
        composeProfiles.each { profile, dir ->
            composeDown(profile)
        }
    }

}