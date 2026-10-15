package io.github.dotnetsupport.frameworks.grpc

import io.github.dotnetsupport.lang.semantic.CSharpCodeGenerators
import io.github.dotnetsupport.lang.semantic.CSharpProjectFacts

/** Grpc.Tools: `Protobuf_Compile` makes the messages and the service bases of `.proto` in `obj/`, known once they are there and fresh (0.1.82). */
class GrpcCodeGenerators : CSharpCodeGenerators {
    override fun mayGenerateTypes(project: CSharpProjectFacts): Boolean =
        !project.buildGeneratedKnown && (project.hasPackage("Grpc.Tools") || project.hasFiles("proto"))
}
