# ecommerce

```bash
cd infrastructure

terraform init

# first create ECR repository
terraform apply \
-target=aws_ecr_repository.productdb \
-target=aws_ecr_repository.customerdb \
-target=aws_ecr_repository.shoppingcartdb \
-target=aws_ecr_repository.rabbitmq \
-target=aws_ecr_repository.product \
-target=aws_ecr_repository.credit_card \
-target=aws_ecr_repository.warehouse \
-target=aws_ecr_repository.shopping_cart


# give permisson to script files
chmod +x ./*.sh

# build and push images to ECR
./push_productdb_image.sh
./push_customerdb_image.sh
./push_shoppingcartdb_image.sh
./push_rabbitmq_image.sh
./push_product_image.sh
./push_credit_card_image.sh
./push_warehouse_image.sh
./push_shopping_cart_image.sh

# then create everything else
terraform apply

# remember to destroy when done
terraform destroy
```

To let the database work, every node needs its `peers` list. Each DB container's `entrypoint.sh` discovers its peers automatically at startup. Terraform passes `ECS_CLUSTER_NAME` and `ECS_SERVICE_NAME` to the task, and the script asks ECS for the private IPs of the other tasks in the same service.

**Security note:** `/config/peers`, `/propagate` and the direct DB write routes have no authentication, so they must never be reachable from the internet. That is why:
- the three DB load balancers and the RabbitMQ NLB are **internal**, reachable only from inside the VPC;
- ECS tasks accept port 8080 only from the load balancers and from each other.

A node that started before its peers were running can end up with an incomplete list. To set peers by hand, run `curl` against `/config/peers` **from inside the VPC**, for example from an EC2 instance or a CloudShell VPC environment in the default VPC. You can get the private IPs of the nodes from the AWS console. For example, if we have five nodes with private IPs

```
172.31.73.175
172.31.26.167
172.31.45.54
172.31.54.120
172.31.95.30
```

we can set the peers as follows:

```bash
curl -X POST "{{db_baseUrl}}/config/peers" \
     -H "Content-Type: application/json" \
     -d '[
            "http://172.31.73.175:8080",
            "http://172.31.26.167:8080",
            "http://172.31.45.54:8080",
            "http://172.31.54.120:8080",
            "http://172.31.95.30:8080"
         ]'
```

The baseUrl can be each node's **private** IP (`http://<private-ip>:8080`), or the internal DB load balancer address to set the peers for all nodes at once. If using the load balancer address, run the command multiple times until all nodes have their peers set.
