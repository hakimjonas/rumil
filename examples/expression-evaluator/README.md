# Expression Evaluator Examples

This directory demonstrates building an arithmetic expression parser and evaluator using Rumil's parser combinators.

## What These Examples Show

- Parsing arithmetic expressions with proper operator precedence
- Handling parentheses and nested expressions
- Two different approaches to expression parsing

## Examples

### 1. StructuralExample.scala

**Direct Evaluation Approach**

- Parses expressions directly to `Int` values
- No intermediate AST
- Evaluates as it parses
- Simpler code, but less flexible

**Features:**
- Operator precedence (multiplication before addition)
- Parentheses for grouping
- Left-associative operators

**Example:**
```
Input:  "2+3*4"
Output: 14  (parsed as 2+(3*4))

Input:  "(2+3)*4"
Output: 20  (parsed as (2+3)*4)
```

### 2. IdiomaticExample.scala

**AST Building Approach**

- Parses expressions to an abstract syntax tree (AST)
- Separates parsing from evaluation
- More flexible: can optimize, pretty-print, analyze, etc.
- Shows idiomatic Scala with enums and pattern matching

**Features:**
- Explicit AST representation using Scala 3 enums
- Two-stage process: parse → evaluate
- Can inspect/transform the AST before evaluation
- Demonstrates recursive data structures

**Example:**
```
Input:  "2+3*4"
AST:    Add(Num(2), Mul(Num(3), Num(4)))
Output: 14

Input:  "(2+3)*4"
AST:    Mul(Add(Num(2), Num(3)), Num(4))
Output: 20
```

## Running the Examples

```bash
# Direct evaluation approach
scala-cli run StructuralExample.scala

# AST building approach
scala-cli run IdiomaticExample.scala
```

## Comparison

| Aspect | Structural (Direct Eval) | Idiomatic (AST) |
|--------|-------------------------|-----------------|
| **Simplicity** | Simpler | More complex |
| **Flexibility** | Limited | High |
| **Use case** | Quick calculators | Compilers, interpreters |
| **Output** | Values only | AST + values |
| **Optimization** | No | Yes (can transform AST) |
| **Error reporting** | Basic | Can be enhanced |

## Key Takeaways

1. **Precedence**: Use `chainl1` for left-associative operators
2. **Recursion**: Expression grammars are naturally recursive
3. **Parser.Custom**: Needed for recursive parser references
4. **Two-stage design**: Parse → AST → Evaluate is more maintainable
5. **Scala 3 enums**: Perfect for representing AST nodes

## Extension Ideas

Try extending these examples with:

- Subtraction and division operators
- Floating-point numbers
- Variables (e.g., `x + 2`)
- Functions (e.g., `sin(x)`)
- Better error messages with position tracking
