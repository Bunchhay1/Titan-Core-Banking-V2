// Sample WASM rule: amount > 10
// Compile with: wat2wasm simple-rule.wat -o simple-rule.wasm

(module
  (func $validate (param $amount f64) (result i32)
    local.get $amount
    f64.const 10.0
    f64.gt
  )
  (export "validate" (func $validate))
)
