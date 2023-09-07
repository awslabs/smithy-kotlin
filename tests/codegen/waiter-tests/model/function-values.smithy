$version: "2"
namespace com.test

use smithy.waiters#waitable

@waitable(
    ValuesFunctionPrimitivesStringEquals: {
        acceptors: [
            {
                state: "success",
                matcher: {
                    output: {
                        path: "values(primitives)",
                        expected: "foo",
                        comparator: "anyStringEquals"
                    }
                }
            }
        ]
    },
    ValuesFunctionSampleValuesEquals: {
        acceptors: [
            {
                state: "success",
                matcher: {
                    output: {
                        path: "values(sampleValues)",
                        expected: "foo",
                        comparator: "allStringEquals"
                    }
                }
            }
        ]
    },
)
@readonly
@http(method: "GET", uri: "/values/{name}", code: 200)
operation GetFunctionValuesEquals {
    input: GetEntityRequest,
    output: GetEntityResponse,
    errors: [NotFound],
}