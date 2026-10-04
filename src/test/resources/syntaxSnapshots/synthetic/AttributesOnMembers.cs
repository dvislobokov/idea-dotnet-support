using System;
using Xunit;
using NUnit.Framework;

[assembly: CLSCompliant(true)]

namespace Synthetic.Tests
{
    [TestFixture]
    public class OrderTests
    {
        [Fact]
        public void PlainFact() { }

        [Theory, InlineData(1), InlineData(2)]
        public async Task TheoryWithData(int value) { await Task.Delay(value); }

        [Xunit.Fact]
        public void QualifiedFact() { }

        [TestCase(1)]
        [TestCase(2)]
        public void ManyCases(int x) { }

        [Test]
        public void Generic<T>() { }

        [Obsolete("{ not a brace }")]
        [Fact(Skip = "later")]
        public void SkippedFact() { }

        [Fact] public void OneLine() { }

        public void NotATest([FromBody] string body) { }

        [field: NonSerialized]
        public event EventHandler Changed;

        public class Nested
        {
            [Test]
            public void InNested() { }
        }
    }

    public struct StructTests
    {
        [Fact]
        public void InStruct() { }
    }

    public interface ITests
    {
        [Fact]
        void InInterface();
    }
}
